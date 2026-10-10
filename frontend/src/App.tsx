import { Navigate, NavLink, Outlet, Route, Routes } from "react-router";
import {
  Bot,
  Blocks,
  MessageSquare,
  Plug,
  Cpu,
  ScrollText,
  LogOut,
  type LucideIcon,
} from "lucide-react";
import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import { useAuthStore } from "@/stores/auth";
import LoginPage from "@/pages/login/LoginPage";
import ChatPage from "@/pages/chat/ChatPage";
import AuditPage from "@/pages/admin/audit/AuditPage";
import ModelsPage from "@/pages/admin/models/ModelsPage";
import AgentsPage from "@/pages/admin/agents/AgentsPage";
import SkillsPage from "@/pages/admin/skills/SkillsPage";
import McpPage from "@/pages/admin/mcp/McpPage";

interface NavItem {
  to: string;
  label: string;
  icon: LucideIcon;
}

const NAV_ITEMS: NavItem[] = [
  { to: "/chat", label: "聊天", icon: MessageSquare },
  { to: "/admin/models", label: "模型", icon: Cpu },
  { to: "/admin/agents", label: "Agent", icon: Bot },
  { to: "/admin/skills", label: "Skill", icon: Blocks },
  { to: "/admin/mcp", label: "MCP", icon: Plug },
  { to: "/admin/audit", label: "审计", icon: ScrollText },
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
        <div className="flex h-14 items-center px-4 text-base font-semibold">my-agent</div>
        <nav className="flex flex-1 flex-col gap-1 p-2">
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
        <div className="flex items-center gap-2 border-t p-2 text-xs">
          <span className="flex-1 truncate" title={user?.username}>
            {user?.displayName ?? user?.username}
            <span className="text-muted-foreground"> ({user?.role})</span>
          </span>
          <Button variant="ghost" size="icon" className="size-7" onClick={clear} title="退出登录">
            <LogOut className="size-3.5" />
          </Button>
        </div>
      </aside>
      <main className="min-w-0 flex-1 overflow-hidden">
        <Outlet />
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
        <Route path="chat" element={<ChatPage />} />
        <Route path="admin/models" element={<ModelsPage />} />
        <Route path="admin/agents" element={<AgentsPage />} />
        <Route path="admin/skills" element={<SkillsPage />} />
        <Route path="admin/mcp" element={<McpPage />} />
        <Route path="admin/audit" element={<AuditPage />} />
        <Route path="*" element={<Placeholder title="页面不存在" />} />
      </Route>
    </Routes>
  );
}
