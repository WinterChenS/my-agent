import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useNavigate, useParams } from "react-router";
import type { HttpAgent } from "@ag-ui/client";
import { Bot, ChevronDown, Clock, Files, Plus, Send, Square } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { MessageBubble } from "@/components/chat/MessageBubble";
import { PendingIndicator } from "@/components/chat/PendingBubble";
import { ArtifactBrowserPopover } from "@/components/chat/ArtifactBrowserPopover";
import { ArtifactBox } from "@/components/chat/ArtifactBox";
import { ArtifactPreviewPanel } from "@/components/chat/ArtifactPreviewPanel";
import { isImageArtifact, listArtifacts, type ArtifactInfo } from "@/api/artifacts";
import { canPreviewArtifact } from "@/lib/artifacts/previewKind";
import { PREVIEW_PANEL_DEFAULT } from "@/lib/chat/previewPanelWidth";
import { AttachmentStaging } from "@/components/chat/AttachmentStaging";
import { ThinkingBlock } from "@/components/chat/ThinkingBlock";
import {
  SkillMentionInput,
  type SkillMentionInputHandle,
} from "@/components/chat/SkillMentionInput";
import { ContextRing } from "@/components/chat/ContextRing";
import { ConnectorMenu } from "@/components/chat/ConnectorMenu";
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
  getContextUsage,
  getRunState,
  interruptSession,
  listAgents,
  listMessages,
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
import { useSessionStore } from "@/stores/sessions";
import { listSkills, type SkillInfo } from "@/api/skills";
import { partsAreEmpty, partsSkillIds, partsToText, type MentionPart } from "@/lib/skills/selection";
import { probeRunningRun } from "@/lib/chat/resumeProbe";
import { newId } from "@/lib/id";

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
  // 会话列表已提到全局 store：它现在渲染在**菜单栏下半部分**（任意页面可见、可点开）
  const refreshSessions = useSessionStore((s) => s.refresh);
  /**
   * URL 路径里的会话 id（`/chat/1033`）—— 它是「当前打开哪个会话」的**唯一来源**。
   *
   * <p>好处有三个：① 打开某条会话的链接可以复制粘贴/分享，刷新也能还原；
   * ② 点菜单「聊天」得到的是 `/chat`（无路径参数）→ 干净的新会话欢迎页（用户要求）；
   * ③ 不必再维护"跨页面打开请求"这类组件间暗号。
   */
  const navigate = useNavigate();
  const { sessionId: sessionParam } = useParams<{ sessionId?: string }>();
  const urlSessionId = sessionParam ? Number(sessionParam) : null;
  /** 打开动作完成前不显示欢迎页，避免"空会话"闪一下 */
  const [booted, setBooted] = useState(false);
  /** 上一次的 session 参数：用来判断「有到无」的转变（见下面 effect 的说明） */
  const prevSessionParam = useRef<string | undefined>(undefined);
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
   * 每项都能「撤回」（内容回到输入框）。
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
  /** 支出额度状态（tier1-upgrades ④）。 */
  const [quota, setQuota] = useState<QuotaStatus | null>(null);
  /** 正在后台跑、需要盯着结束的会话（id → 标题）。 */
  const watchingRuns = useRef<Map<number, string>>(new Map());
  // Phase 10：`/` 选技能（D21）、上传中状态、产物清单刷新信号
  const [skills, setSkills] = useState<SkillInfo[]>([]);
  const [artifactRefresh, setArtifactRefresh] = useState(0);
  /**
   * 本会话的产物（用于**气泡下方**的产物框）。
   *
   * <p>与清单弹窗各自取一份数据：那边是"整个会话的清单"，这边要按 `runId` 精确挂到产出它的那一轮。
   */
  const [artifacts, setArtifacts] = useState<ArtifactInfo[]>([]);
  /**
   * 右侧预览面板：打开的产物做成 tab（点气泡图片、产物框、清单弹窗都能往这里加）。
   *
   * <p>面板是**内联挤压**聊天区的（不是遮罩抽屉），可以整体关闭，也可以逐个关 tab。
   * 开关状态**不持久化**（刷新回默认）：出现可预览产物就自动打开。
   */
  const [previewTabs, setPreviewTabs] = useState<ArtifactInfo[]>([]);
  const [activePreviewId, setActivePreviewId] = useState<number | null>(null);
  const [previewOpen, setPreviewOpen] = useState(false);
  /**
   * 预览面板宽度（px）：**拖左边界**调整它和聊天区的比例（用户要求）。
   *
   * <p>默认 448px，不持久化（刷新回默认，与面板开关一致）。
   */
  const [previewWidth, setPreviewWidth] = useState(PREVIEW_PANEL_DEFAULT);
  /** 已经“自动打开过”的产物 id：同一条产物刷新时不要再抢焦点 */
  const seenPreviewIds = useRef<Set<number>>(new Set());

  /** 在右侧面板里打开一个产物（已在 tab 里就只切过去）。 */
  const openPreview = useCallback((item: ArtifactInfo) => {
    setPreviewTabs((prev) => (prev.some((tab) => tab.id === item.id) ? prev : [...prev, item]));
    setActivePreviewId(item.id);
    setPreviewOpen(true);
  }, []);

  /**
   * 关掉一个 tab。
   *
   * <p>关掉**当前**这个时把选中项交给相邻的 tab（否则内容区会空着但面板还在），
   * 全关光了才把面板一起收起来。
   */
  const closePreviewTab = useCallback(
    (id: number) => {
      const index = previewTabs.findIndex((tab) => tab.id === id);
      const next = previewTabs.filter((tab) => tab.id !== id);
      setPreviewTabs(next);
      // 关最后一个 → 面板一起收起（否则会剩一个空壳面板）
      if (next.length === 0) {
        setPreviewOpen(false);
      }
      setActivePreviewId((current) =>
        current === id
          ? (next[Math.min(Math.max(index, 0), next.length - 1)]?.id ?? null)
          : current,
      );
    },
    [previewTabs],
  );

  /** 面板是否真的可见（开着且有 tab 才算） */
  const panelVisible = previewOpen && previewTabs.length > 0;
  const [uploading, setUploading] = useState(false);
  // Phase 13：拖拽进聊天区时高亮提示
  const [dragging, setDragging] = useState(false);
  // Phase 11 / D24：附件先暂存在浏览器，发送时才上传
  const [staged, setStaged] = useState<StagedAttachment[]>([]);

  const agentRef = useRef<HttpAgent | null>(null);
  /** 接续「正在跑的 run」的令牌：换会话/重复接续时让旧循环失效（见 resumeRunningRun） */
  const resumeToken = useRef(0);
  /**
   * 本页**自己**正在流式跑的那一轮（实时流的归属）。
   *
   * <p>为什么要跟踪：接续（{@link resumeRunningRun}）和实时流会写同一轮的气泡。两边同时写时，
   * 接续拿到的是**轮询快照**（1.2s 一次），可能比实时流**旧** —— 会把更新的文本改回旧的；
   * 收尾时还会用 listMessages 整体替换界面，把正在等用户操作的卡片抹掉。
   * 实测（全量跑）：
   *  - 「切走再切回」用例：第一条答案被改短 1 个字符，断言 toHaveText 失败；
   *  - 「交互卡片」用例：点「继续」后卡片不消失，45s 等待超时。
   * 所以：这一轮**归实时流**，接续不插手。页面刷新进来时这张表是空的，所以
   * 「刷新后接续 / 别的标签页的 run」这些真正需要接续的场景不受影响。
   *
   * <p>用 Map（而不是单个 id）：用户可能在 A 会话还跑着时切到 B 再发一条，两条 run 同时流式，
   * 两个会话都得受保护。值是本轮的唯一令牌，避免旧一轮的收尾把新登记的那条清掉。
   */
  const liveRunsRef = useRef<Map<number, number>>(new Map());
  /** 实时流轮次序号：排队消息会再起一轮，用它保证只有**最后一次**收尾能清掉登记 */
  const liveRunSeq = useRef(0);
  /**
   * **当前显示的**会话 id（ref，供异步回调读取）。
   *
   * <p>为什么必须有：一轮 run 的流式回调（以及收尾逻辑）是绑在"发起它的那个会话"上的。
   * 用户切到别的会话后，这些回调如果照旧写 state，就会把 **A 的内容写进正在看的 B** ——
   * 这就是用户反馈的"切走再切回，对话错乱"（实测复现）。有了它，回调可以判断
   * "我现在代表的会话还是屏幕上那个吗"，不是就闭嘴（服务端那一轮照跑，回来再接续）。
   */
  const activeSessionIdRef = useRef<number | null>(null);
  const fileRef = useRef<HTMLInputElement | null>(null);
  const bottomRef = useRef<HTMLDivElement | null>(null);

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

  /**
   * `/` 候选技能 = **当前 Agent 绑定的**启用技能。
   *
   * <p>为什么按 Agent 过滤（2026-10-23 用户反馈）：模型的 `<available_skills>` 只含绑定的技能，
   * 未绑定的在对话里根本调不到。以前这里拉的是全平台 ENABLED 技能，用户选了没绑定的，
   * 模型只会回一句"技能加载失败"。
   *
   * <p>会话已有 agentId 时以会话为准（下拉框只影响新会话）。
   */
  const skillsForAgent = session?.agentId ?? agentId;
  useEffect(() => {
    if (skillsForAgent == null) {
      setSkills([]);
      return;
    }
    listSkills(1, 100, "ENABLED", skillsForAgent)
      .then((page) => setSkills(page.records))
      .catch(() => {
        /* 技能拉取失败不影响聊天 */
      });
  }, [skillsForAgent]);

  // 兜底：会话状态一变就同步 ref（openSession/startNewSession 里还会同步设一次，避免时序空档）
  useEffect(() => {
    activeSessionIdRef.current = session?.id ?? null;
  }, [session?.id]);

  useEffect(() => {
    bottomRef.current?.scrollIntoView?.({ behavior: "smooth" });
  }, [chat.items]);

  // 换会话：预览面板整个清空（tab 是「这条会话的产物」，跨会话留着只会指错东西）
  useEffect(() => {
    setPreviewTabs([]);
    setActivePreviewId(null);
    setPreviewOpen(false);
    seenPreviewIds.current = new Set();
  }, [session?.id]);

  /**
   * 「来源文件与产物」清单的触发按钮（**两个入口共用一个组件**：页头一个，面板左上角一个）。
   *
   * <p>弹窗紧贴着**自己身下的按钮**弹出，所以触发按钮必须由弹窗组件包裹（Radix 靠这层关系锚定）。
   */
  const browserTrigger = (testId: string) => (
    <ArtifactBrowserPopover
      sessionId={session?.id ?? null}
      refreshKey={artifactRefresh}
      onError={setError}
      onPreview={openPreview}
    >
      <Button
        variant="ghost"
        size="icon"
        className="size-7 shrink-0"
        title="来源文件与产物"
        data-testid={testId}
      >
        <Files className="size-4" />
      </Button>
    </ArtifactBrowserPopover>
  );

  // 取本会话产物：换会话、以及每轮结束（artifactRefresh 自增）时刷新，供气泡下方展示
  useEffect(() => {
    if (session?.id == null) {
      setArtifacts([]);
      return;
    }
    let cancelled = false;
    listArtifacts(session.id)
      .then((list) => {
        if (!cancelled) {
          setArtifacts(list.filter((item) => item.role === "ARTIFACT"));
        }
      })
      .catch(() => {
        if (!cancelled) {
          setArtifacts([]);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [session?.id, artifactRefresh]);

  /**
   * 本会话出现**新的可预览产物**时自动打开面板。
   *
   * <p>“新”按 id 记在 `seenPreviewIds` 里：每轮结束都会重拉产物列表，同一个文件不该反复抢焦点；
   * 面板已经被用户关掉时也不再自动弹（用户关它就是要别看）。
   *
   * <p>还必须限住 `sessionId`：换会话那一帧里 `artifacts` 还是**上一条会话**的旧值，
   * 不筛就会把上一条会话的产物自动开到新会话上。
   */
  useEffect(() => {
    if (session?.id == null) {
      return;
    }
    const fresh = artifacts.filter(
      (item) =>
        item.sessionId === session.id &&
        canPreviewArtifact(item) &&
        !seenPreviewIds.current.has(item.id),
    );
    if (fresh.length === 0) {
      return;
    }
    fresh.forEach((item) => seenPreviewIds.current.add(item.id));
    if (!previewOpen) {
      openPreview(fresh[0]);
    }
  }, [artifacts, previewOpen, openPreview, session?.id]);

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
      /*
       * 接续令牌：每次发起接续就换一个号，旧的接续循环据此**立刻停止**。
       *
       * 为什么必须有（实测的错乱来源）：用户在 A 会话来时起了一个轮询循环，随后切到 B 会话 ——
       * 若旧循环不停，它会继续把 **A 的半截文本**写进正在显示的 B，收尾时还会用
       * `replayMessages(A 的消息)` 整个替换界面 —— 对话就串了。
       */
      const token = (resumeToken.current += 1);
      /*
       * `active()` 除了「令牌没被换掉」，还要确认**本页没有在实时跑这一轮**：
       * 本页自己发起的 run 由实时流负责写入，接续插手只会把更新的内容改回旧快照
       * （甚至把待确认卡片抹掉）——见 liveRunsRef 的说明。
       */
      const active = () => token === resumeToken.current && !liveRunsRef.current.has(target.id);

      const applyPartial = (text: string, runIdFromApi: string | null) => {
        if (!text || !active()) {
          return;
        }
        /*
         * runId 来自 /run 接口（服务端这一轮的 run id）——**必须写进这条气泡**：
         * 它是与"实时 SSE 流"归并的依据（同 runId 只保留一条气泡，避免重复）。
         */
        const itemRunId = runIdFromApi ?? null;
        setChat((prev) => {
          const items = [...prev.items];
          /*
           * 只更新**本轮**（最后一条用户消息之后）的助手气泡。
           *
           * 不能盲目覆盖"最后一条助手文本"：本轮消息还没落库时，最后一条助手文本其实是
           * **上一轮的答案**，覆盖它就把上一轮的回答改成了本轮的半截文本（实测的错乱来源之一）。
           */
          let lastUserIndex = -1;
          items.forEach((item, index) => {
            if (item.kind === "text" && item.role === "user") {
              lastUserIndex = index;
            }
          });
          for (let i = items.length - 1; i > lastUserIndex; i -= 1) {
            const item = items[i];
            if (item.kind === "text" && item.role === "assistant") {
              items[i] = {
                ...item,
                /*
                 * 同一轮的文本**只会增长**：轮询快照可能落后于实时流，
                 * 照抄会把已经显示出来的后续内容改短（实测：答案被改短 1 个字符）。
                 */
                content: text.length >= item.content.length ? text : item.content,
                streaming: true,
                stopped: false,
                runId: item.runId ?? itemRunId ?? prev.runId,
              };
              return { ...prev, items, status: "running", runId: itemRunId ?? prev.runId };
            }
          }
          // 本轮还没有助手气泡 → **追加**一个（绝不能覆盖上一轮的答案）
          return {
            items: [
              ...items,
              {
                kind: "text",
                id: `resumed-${target.id}`,
                role: "assistant",
                content: text,
                streaming: true,
                runId: itemRunId ?? prev.runId,
              },
            ],
            status: "running",
            runId: itemRunId ?? prev.runId,
          };
        });
        setBusy(true);
      };

      /*
       * 首次探测**要重试**，不能一次判定。
       *
       * 刷新页面会中断还在途的 POST /agui/run，而服务端是收到请求、解析完 Agent 之后才
       * 登记这一轮（RunningRunRegistry.beginRun）。于是"刷新那一刻"探到的 running 可能还是 false ——
       * 若就此放弃，界面会永远停在「只有上一轮答案、没有本轮气泡、也没有停止按钮」，
       * 而且此后没有任何东西会再触发接续（实测：全量跑时报「助手气泡 toHaveCount(2) 实得 1」，
       * 单独跑时后端更快、恰好登记完了所以是绿的）。重试窗口见 probeRunningRun。
       */
      const state = await probeRunningRun(
        () => getRunState(target.id).catch(() => null),
        (snapshot) => snapshot.running,
        { active },
      );
      // 再确认一次 active()：探测期间用户可能刚好发了消息（那一刻起归实时流，接续要让位）
      if (!state || !active()) {
        return false;
      }
      applyPartial(state.text, state.runId ?? null);

      // 轮询到这一轮结束（结束/宽限期超时后服务端会落库）
      const deadline = Date.now() + 10 * 60 * 1000;
      /** 连续失败次数：偶发失败（网络抖动、后端刚重启）不该让接续直接断掉 */
      let failures = 0;
      while (Date.now() < deadline) {
        await new Promise((resolve) => setTimeout(resolve, 1200));
        if (!active()) {
          // 用户已经切走（或又发起了一次接续）：立即停手，不要再往界面写任何东西
          return false;
        }
        const next = await getRunState(target.id).catch(() => null);
        if (!next) {
          failures += 1;
          // 只在连续失败多次后才放弃：一次失败就 break 会让"刷新后接续"静默失效（实测踩到）
          if (failures >= 5) {
            break;
          }
          continue;
        }
        failures = 0;
        applyPartial(next.text, next.runId ?? null);
        if (!next.running) {
          break;
        }
      }
      if (!active()) {
        return false;
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




  const startNewSession = useCallback(() => {
    setSession(null);
    activeSessionIdRef.current = null;
    setChat(initialChatState);
    agentRef.current = createChatAgent();
    setError(null);
    // 复位 busy：上一个会话的 run 可能还在后台跑，但“正在生成”不该绑住新会话的输入框
    // （Phase 14 的设计就是“切走后台继续跑”，所以切过来必须能接着打字）
    setBusy(false);
    // 新会话把上下文用量也清掉，避免残留到空白新会话里
    setContextUsage(null);
    setContextNotice(null);
  }, []);

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
    // 立刻标记"当前显示的是它"，别等 effect（否则切换瞬间旧会话的回调可能还写得进来）
    activeSessionIdRef.current = target.id;
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

  /**
   * 以 URL 为准打开会话：`/chat?session=N` → 打开 N；`/chat`（无参数）→ 欢迎页。
   *
   * <p>为什么这么做：用户要求「点菜单『聊天』就是欢迎页」，同时「打开过的会话要能复制粘贴/
   * 刷新还原」。把 URL 当唯一来源后，两件事都自然成立，也不再需要"跨页面打开请求"这种暗号。
   *
   * <p>找不到就刷一次列表再找：可能是别的标签页/别处新建、或列表还没加载。
   */
  useEffect(() => {
    const prevParam = prevSessionParam.current;
    prevSessionParam.current = sessionParam;
    if (urlSessionId === null) {
      /*
       * 无参数 = 欢迎页。但**只有从有到无的转变**才回欢迎页（用户点了「聊天」/删了会话）。
       *
       * 不能写成「只要无参数就把现有会话清掉」：URL 参数与 session 状态并非同一次渲染生效 ——
       * 第一轮发消息时会出现「session 已建好、URL 参数还没写进去」的中间帧，那样会把刚建的会话清掉，
       * 于是第二轮发消息时上文全没了（实测：多轮对话/排队/侧栏标记等 5 条 e2e 因此失败）。
       */
      if (prevParam !== undefined && prevParam !== null) {
        startNewSession();
      }
      setBooted(true);
      return;
    }
    if (session?.id === urlSessionId) {
      setBooted(true);
      return;
    }
    let cancelled = false;
    void (async () => {
      try {
        let target = useSessionStore
          .getState()
          .sessions.find((item) => item.id === urlSessionId);
        if (!target) {
          const list = await refreshSessions();
          target = list.find((item) => item.id === urlSessionId);
        }
        if (cancelled) {
          return;
        }
        if (target) {
          await openSession(target);
        } else {
          setError("这条会话不存在或已被删除");
        }
      } finally {
        if (!cancelled) {
          setBooted(true);
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [urlSessionId, sessionParam, session, openSession, refreshSessions, startNewSession, navigate]);

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
          id: newId(),
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

    /** 这一轮的界面写入是否还有效（用户切走了就不要再写，否则串到别的会话上） */
    const stillOnScreen = () => activeSessionIdRef.current === active.id;
    /*
     * 登记「这一轮的实时流归本页」——只在 runAgent 期间有效，收尾时释放。
     * 接续（resumeRunningRun）看到这里登记了自己就会让位（详见 liveRunsRef 的说明）。
     */
    const myLiveToken = (liveRunSeq.current += 1);
    liveRunsRef.current.set(active.id, myLiveToken);
    if (stillOnScreen()) {
      setChat((prev) => ({ ...prev, status: "running" }));
      setBusy(true);
    }
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
            if (!stillOnScreen()) {
              return;
            }
            setChat((prev) => reduceEvent(prev, event as never));
          },
          onRunFailed: ({ error: runError }) => {
            if (!stillOnScreen()) {
              return;
            }
            setError(runError.message);
            setChat((prev) => ({ ...prev, status: "error" }));
          },
        },
      );
      if (stillOnScreen()) {
        setChat((prev) => (prev.status === "running" ? { ...prev, status: "finished" } : prev));
      }
      // 排队：这一轮跑完，自动把队首那条接着发出去（一次发一条，避免连发把上下文打乱）
      // 注意用 queuedRef（当前值），不能用闭包里的 queued（那是这一轮启动时的旧值）
      if (queuedRef.current.length > 0) {
        const next = queuedRef.current[0];
        setQueued((prev) => prev.slice(1));
        // 与手动发送一致：先补一条**本地回显气泡**，否则消息发到后端了、界面上却看不到
        if (stillOnScreen()) {
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
        }
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
      // 只有**最后一次**登记的实时流才清得掉（排队消息会在收尾前再起一轮）
      if (liveRunsRef.current.get(active.id) === myLiveToken) {
        liveRunsRef.current.delete(active.id);
      }
      if (stillOnScreen()) {
        setBusy(false);
        void refreshContext(session);
      }
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
   * 发送入口：外面包一层 try/catch。
   *
   * <p>为什么必须有：之前 `crypto.randomUUID` 在非安全上下文（内网 http 部署）不存在，
   * 一按发送就抛 TypeError —— 而这里是 async 事件回调，异常**不会**被 React 捕获，
   * 表现就是"点了发送毫无反应、停止按钮也不出现、也没有任何报错"，排查成本极高。
   */
  async function send() {
    try {
      await sendInner();
    } catch (err) {
      setError(err instanceof Error ? err.message : "发送失败");
    }
  }

  async function sendInner() {
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
          id: newId(),
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
        // id 立刻进 URL（/chat/<id>）：可复制粘贴分享，刷新也能重新打开这条会话
        void navigate(`/chat/${active.id}`, { replace: true });
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
  /**
   * 待处理的交互卡片（确认卡 / 审批卡 / 映射卡……）。
   *
   * <p>判定规则不需要额外状态：**出现在最后一条用户消息之后**的卡片就是"还没被回答"的。
   * 用户一旦作出选择，界面会追加一条用户消息，卡片就落到它前面、不再悬浮 —— 卡片随即淡出。
   *
   * <p>为什么放这里而不是气泡里：用户明确要求卡片以**悬浮弹窗**形式出现在输入框上方，
   * 而不是夹在聊天记录中间（夹在中间会随滚动跑掉，且打断阅读）。
   */
  const pendingCards = useMemo(() => {
    const items = chat.items;
    let lastUserIndex = -1;
    items.forEach((item, index) => {
      if (item.kind === "text" && item.role === "user") {
        lastUserIndex = index;
      }
    });
    return items.slice(lastUserIndex + 1).filter((item) => item.kind === "card" || item.kind === "approval");
  }, [chat.items]);

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

      if (item.kind === "thinking") {
        // 思考过程：思考中展开、结束自动折叠（不落库，刷新后消失）
        nodes.push(<ThinkingBlock key={item.id} item={item} />);
        i += 1;
        continue;
      }

      if (item.kind === "subagent") {
        nodes.push(<SubagentCard key={item.id} item={item} />);
      } else if (item.kind === "text") {
        // 本轮的产物：图片进气泡内；其余仍在气泡下方的产物框
        const own =
          item.role === "assistant" && item.runId
            ? artifacts.filter((artifact) => artifact.runId === item.runId)
            : [];
        const ownFiles = own.filter((artifact) => !isImageArtifact(artifact));
        nodes.push(
          <MessageBubble
            key={item.id}
            role={item.role}
            content={item.content}
            streaming={item.streaming}
            stopped={item.stopped}
            artifacts={item.role === "assistant" ? own : undefined}
            onOpenArtifact={openPreview}
          />,
        );
        if (ownFiles.length > 0) {
          nodes.push(
            <ArtifactBox
              key={`${item.id}-artifacts`}
              items={ownFiles}
              onOpen={openPreview}
            />,
          );
        }
      } else if (item.kind === "approval" || item.kind === "card") {
        // 交互卡片不再渲染在气泡流里：统一由输入框上方的悬浮层呈现（见 cardOverlay）
        i += 1;
        continue;
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

  /** 欢迎页：还没有任何对话内容时，把输入框居中并给出引导文案（对齐 ChatGPT 的空白态） */
  const isEmptyState = booted && !session && chat.items.length === 0;

  /**
   * 是否显示「正在思考」占位气泡。
   *
   * <p>判据是「**本轮还什么都没显示**」：在最后一条用户消息之后，只要已经出现任何 item
   * （助手正文、工具调用行、交互卡片、子 Agent 卡片……），就说明界面上已经有东西了，不再需要占位。
   *
   * <p>只有一种情况要占位：这一轮在跑，但用户消息后面**空空如也** —— 模型在首字之前想了很久
   * （推理型模型尤其明显），界面看着像没反应。
   *
   * <p>（早先只看"有没有助手正文"，于是工具调用行已经显示出来了、占位气泡还挂在下面 —— 用户实测指出。）
   */
  const lastUserIndex = chat.items.reduce(
    (found, item, index) => (item.kind === "text" && item.role === "user" ? index : found),
    -1,
  );
  const turnHasContent = chat.items.length > lastUserIndex + 1;
  const showPendingBubble = chat.status === "running" && !turnHasContent;

  return (
    <div className="flex h-screen">

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
            {/* 来源/产物清单的入口（面板没开时也能从这里进去） */}
            {browserTrigger("open-artifact-browser")}
            <span className="text-sm font-medium">聊天</span>
            {chat.status === "running" && <Badge variant="secondary">生成中…</Badge>}
            {chat.status === "error" && <Badge variant="destructive">出错</Badge>}
          </div>
        </header>

        {/* 欢迎页：上撑开 + 文案，配合输入框下方的撑开块，使输入框垂直居中 */}
        {isEmptyState && <div className="flex-1" />}
        {isEmptyState && (
          <div className="mx-auto w-full max-w-3xl px-4 pb-5 text-center" data-testid="welcome">
            <h1 className="text-2xl font-semibold tracking-tight">开始新的对话</h1>
            <p className="text-muted-foreground mt-2 text-sm">
              选一个 Agent，直接说清你想要什么。产物会自动收进右侧列表。
            </p>
            <div className="text-muted-foreground mt-4 flex flex-wrap justify-center gap-2 text-xs">
              <span className="bg-muted rounded-full px-2.5 py-1">拖拽文件即可添加附件</span>
              <span className="bg-muted rounded-full px-2.5 py-1">输入 / 引用技能</span>
              <span className="bg-muted rounded-full px-2.5 py-1">执行中可排队、可撤回</span>
              <span className="bg-muted rounded-full px-2.5 py-1">断开也不中断，回来接着看</span>
            </div>
          </div>
        )}

        <div className={isEmptyState ? "hidden" : "flex-1 overflow-auto"}>
          <div data-testid="chat-column" className="mx-auto w-full max-w-3xl space-y-4 p-4">
            {renderItemNodes()}
            {showPendingBubble && <PendingIndicator />}
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
          <div className="relative mx-auto w-full max-w-3xl px-4">
            {/* 交互卡片：悬浮在输入框**上方**（不占布局，覆盖在消息区之上） */}
            {pendingCards.length > 0 && (
              <div
                className="absolute inset-x-4 bottom-full z-20 mb-3 space-y-2"
                data-testid="card-overlay"
              >
                {pendingCards.map((item) =>
                  item.kind === "approval" ? (
                    <ApprovalCard
                      key={item.id}
                      toolName={item.toolName}
                      args={item.args}
                      message={item.message}
                      status={item.status}
                      busy={busy}
                      onDecide={(decision) => void decide(item.interruptId, decision)}
                    />
                  ) : item.card === "confirm" ? (
                    <ConfirmCard
                      key={item.id}
                      data={item.data as ConfirmCardData}
                      busy={busy}
                      onChoose={(value) => void chooseOption(value)}
                    />
                  ) : item.card === "mapping" ? (
                    <MappingCard key={item.id} data={item.data as MappingCardData} />
                  ) : (
                    <JsonCard
                      key={item.id}
                      name={`${item.name}（${item.card}）`}
                      data={item.data}
                    />
                  ),
                )}
              </div>
            )}
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

                <ConnectorMenu agentId={agentId} />

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

        {/* 欢迎页下方撑开：与上方撑开块配合，把「文案 + 输入框」整体居中 */}
        {isEmptyState && <div className="flex-1" />}
      </section>

      {/* 预览面板：内联挤压聊天区（不是遮罩），多产物用 tab 分开 */}
      {panelVisible && (
        <ArtifactPreviewPanel
          tabs={previewTabs}
          activeId={activePreviewId}
          onSelect={setActivePreviewId}
          onCloseTab={closePreviewTab}
          onClose={() => setPreviewOpen(false)}
          width={previewWidth}
          onWidthChange={setPreviewWidth}
          browserTrigger={browserTrigger("open-artifact-browser-panel")}
        />
      )}
    </div>
  );
}
