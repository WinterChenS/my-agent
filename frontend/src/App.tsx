import { Suspense, lazy } from "react";
import { Navigate, NavLink, Outlet, Route, Routes } from "react-router";
import {
  Bot,
  Blocks,
  Brain,
  CalendarClock,
  MessageCircle,
  MessageSquare,
  Plug,
  Cpu,
  ScrollText,
  Users,
  Settings,
  LogOut,
  type LucideIcon,
} from "lucide-react";
import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import { SidebarSection, TabbedSection } from "@/components/admin/SectionNav";
import { SessionList } from "@/components/chat/SessionList";
import { useAuthStore } from "@/stores/auth";
import { useSessionStore } from "@/stores/sessions";
import LoginPage from "@/pages/login/LoginPage";

/**
 * 管理台页面按需加载（bundle 拆分）。
 *
 * 原因：主 chunk 曾达 2.1MB —— 里面塞着 5 个管理台页面，其中技能页还带着整个 MDXEditor（Lexer 系一大票）。
 * 但登录后首屏是聊天页，管理页对绝大多数会话都不需要，没必要让首屏等它们。
 * 用 React.lazy 拆成独立 chunk，进入对应路由时才下载。
 */
// 聊天页也拆出去：未登录用户先看到的是登录页，没必要为它下载聊天页 + markdown 渲染器等一大票
const ChatPage = lazy(() => import("@/pages/chat/ChatPage"));
const MemoryPage = lazy(() => import("@/pages/memory/MemoryPage"));
// 以下是「主菜单 → tab / 二级菜单」里的分页，仍然各自独立成 chunk
const AuditPage = lazy(() => import("@/pages/admin/audit/AuditPage"));
const ModelsPage = lazy(() => import("@/pages/admin/models/ModelsPage"));
const AgentsPage = lazy(() => import("@/pages/admin/agents/AgentsPage"));
const SkillsPage = lazy(() => import("@/pages/admin/skills/SkillsPage"));
const McpPage = lazy(() => import("@/pages/admin/mcp/McpPage"));
const UsersPage = lazy(() => import("@/pages/admin/users/UsersPage"));
const ChannelsPage = lazy(() => import("@/pages/admin/channels/ChannelsPage"));
const SchedulesPage = lazy(() => import("@/pages/admin/schedules/SchedulesPage"));

interface NavItem {
  to: string;
  label: string;
  icon: LucideIcon;
}

const NAV_ITEMS: NavItem[] = [
  { to: "/chat", label: "聊天", icon: MessageSquare },
  { to: "/memory", label: "记忆", icon: Brain },
  // 同族面板收进一个主菜单，进去后再用 tab / 二级菜单区分（条目只增不减，侧栏不再无限变长）
  { to: "/admin/model-agents", label: "Agent 与模型", icon: Bot },
  { to: "/admin/capabilities", label: "技能与 MCP", icon: Blocks },
  { to: "/admin/schedules", label: "定时任务", icon: CalendarClock },
  { to: "/admin/settings", label: "系统设置", icon: Settings },
];

/** 「Agent 与模型」的两个 tab。 */
const MODEL_AGENT_TABS = [
  {
    to: "/admin/model-agents/agents",
    label: "Agent",
    icon: Bot,
    hint: "Agent 配置：人设、模型、权限模式、技能绑定、迭代上限",
  },
  {
    to: "/admin/model-agents/models",
    label: "模型",
    icon: Cpu,
    hint: "模型与供应商：baseUrl、API Key、参数、连通性测试",
  },
];

/** 「技能与 MCP」的两个 tab。 */
const CAPABILITY_TABS = [
  {
    to: "/admin/capabilities/skills",
    label: "Skill",
    icon: Blocks,
    hint: "技能仓库与导入、启用状态、资源文件",
  },
  {
    to: "/admin/capabilities/mcp",
    label: "MCP",
    icon: Plug,
    hint: "MCP 服务接入、工具发现与连接状态",
  },
];

/** 「系统设置」左侧二级菜单（后续还会继续加条目：模型配额、通知、存储…）。 */
const SETTINGS_TABS = [
  {
    to: "/admin/settings/channels",
    label: "IM 渠道",
    icon: MessageCircle,
    hint: "钉钉/飞书等渠道接入与推送",
  },
  {
    to: "/admin/settings/audit",
    label: "审计日志",
    icon: ScrollText,
    hint: "操作与调用审计",
  },
  { to: "/admin/settings/users", label: "用户", icon: Users, hint: "账号与角色管理" },
];

/** 占位页：各 Phase 会替换为真实页面。 */
function Placeholder({ title }: { title: string }) {
  return (
    <div className="p-6">
      <h1 className="text-2xl font-semibold">{title}</h1>
      <p className="text-muted-foreground mt-2 text-sm">
        {title} 页面将在后续 Phase 实现。
      </p>
    </div>
  );
}

/** 未登录跳转到 /login。 */
function RequireAuth({ children }: { children: React.ReactNode }) {
  const token = useAuthStore((s) => s.token);
  if (!token) {
    return <Navigate to="/login" replace />;
  }
  return <>{children}</>;
}

function AppLayout() {
  const user = useAuthStore((s) => s.user);
  const clear = useAuthStore((s) => s.clear);

  return (
    <div className="flex h-screen">
      <aside className="bg-sidebar text-sidebar-foreground flex w-56 shrink-0 flex-col border-r">
        <div className="flex h-14 shrink-0 items-center px-4 text-base font-semibold">
          my-agent
        </div>
        {/* 菜单（上半部分）：条目数量固定，允许被压缩，把更多空间留给会话历史 */}
        <nav className="flex flex-col gap-1 overflow-y-auto p-2 pt-0">
          {NAV_ITEMS.map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              className={({ isActive }) =>
                cn(
                  "flex items-center gap-2 rounded-md px-3 py-2 text-sm transition-colors",
                  isActive
                    ? "bg-sidebar-accent text-sidebar-accent-foreground font-medium"
                    : "hover:bg-sidebar-accent/60",
                )
              }
            >
              <item.icon className="size-4" />
              {item.label}
            </NavLink>
          ))}
        </nav>
        {/* 会话历史（下半部分）：全局可见、跨页面可点开、可刷新 */}
        <SessionList />
        <div className="flex shrink-0 items-center gap-2 border-t p-2 text-xs">
          <span className="flex-1 truncate" title={user?.username}>
            {user?.displayName ?? user?.username}
            <span className="text-muted-foreground"> ({user?.role})</span>
          </span>
          <Button
            variant="ghost"
            size="icon"
            className="size-7"
            onClick={() => {
              // 退出时清掉会话缓存，避免下一个账号看到上一个账号的历史
              useSessionStore.getState().reset();
              clear();
            }}
            title="退出登录"
          >
            <LogOut className="size-3.5" />
          </Button>
        </div>
      </aside>
      <main className="min-w-0 flex-1 overflow-hidden">
        {/* Suspense 放在**布局内部**、只包 Outlet：
            页面 chunk 加载时侧边栏/顶栏保持可见，只让内容区显示占位。
            如果把 Suspense 包在整个 <Routes> 外面，切换管理台时整屏都会被替换掉（含侧边栏）。 */}
        <Suspense
          fallback={
            <div className="text-muted-foreground flex h-full items-center justify-center text-sm">
              加载中…
            </div>
          }
        >
          <Outlet />
        </Suspense>
      </main>
    </div>
  );
}

export default function App() {
  return (
    <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route
          element={
            <RequireAuth>
              <AppLayout />
            </RequireAuth>
          }
        >
          <Route index element={<Navigate to="/chat" replace />} />
          <Route path="chat/:sessionId?" element={<ChatPage />} />
          <Route path="memory" element={<MemoryPage />} />

          {/* Agent 与模型：一个主菜单，两个 tab */}
          <Route
            path="admin/model-agents"
            element={
              <TabbedSection
                title="Agent 与模型"
                description="Agent 是「谁在干活」，模型是「用哪个大脑」；两者一对一绑定。"
                tabs={MODEL_AGENT_TABS}
              />
            }
          >
            <Route index element={<Navigate to="agents" replace />} />
            <Route path="agents" element={<AgentsPage />} />
            <Route path="models" element={<ModelsPage />} />
          </Route>

          {/* 技能与 MCP：都是「给 Agent 装能力」，一并放一个主菜单 */}
          <Route
            path="admin/capabilities"
            element={
              <TabbedSection
                title="技能与 MCP"
                description="Skill 是打包好的方法论与资源，MCP 是外部工具服务；都挂在 Agent 上生效。"
                tabs={CAPABILITY_TABS}
              />
            }
          >
            <Route index element={<Navigate to="skills" replace />} />
            <Route path="skills" element={<SkillsPage />} />
            <Route path="mcp" element={<McpPage />} />
          </Route>

          <Route path="admin/schedules" element={<SchedulesPage />} />

          {/* 系统设置：左侧二级菜单，后续新增设置项只需往 SETTINGS_TABS 里加一条 */}
          <Route
            path="admin/settings"
            element={
              <SidebarSection
                title="系统设置"
                description="平台级配置，与具体 Agent 无关。"
                tabs={SETTINGS_TABS}
                footer="后续还会加更多设置项"
              />
            }
          >
            <Route index element={<Navigate to="channels" replace />} />
            <Route path="channels" element={<ChannelsPage />} />
            <Route path="audit" element={<AuditPage />} />
            <Route path="users" element={<UsersPage />} />
          </Route>

          {/* 老地址保留：书签/历史记录不失效 */}
          <Route path="admin/agents" element={<Navigate to="/admin/model-agents/agents" replace />} />
          <Route path="admin/models" element={<Navigate to="/admin/model-agents/models" replace />} />
          <Route path="admin/skills" element={<Navigate to="/admin/capabilities/skills" replace />} />
          <Route path="admin/mcp" element={<Navigate to="/admin/capabilities/mcp" replace />} />
          <Route path="admin/channels" element={<Navigate to="/admin/settings/channels" replace />} />
          <Route path="admin/audit" element={<Navigate to="/admin/settings/audit" replace />} />
          <Route path="admin/users" element={<Navigate to="/admin/settings/users" replace />} />
          <Route path="*" element={<Placeholder title="页面不存在" />} />
        </Route>
      </Routes>
  );
}
