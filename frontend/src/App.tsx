import { NavLink, Outlet, Route, Routes } from "react-router";
import {
  Bot,
  Blocks,
  MessageSquare,
  Plug,
  Cpu,
  ScrollText,
  type LucideIcon,
} from "lucide-react";
import { cn } from "@/lib/utils";

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

function AppLayout() {
  return (
    <div className="flex min-h-screen">
      <aside className="bg-sidebar text-sidebar-foreground flex w-56 shrink-0 flex-col border-r">
        <div className="flex h-14 items-center px-4 text-base font-semibold">
          my-agent
        </div>
        <nav className="flex flex-col gap-1 p-2">
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
      </aside>
      <main className="flex-1 overflow-auto">
        <Outlet />
      </main>
    </div>
  );
}

export default function App() {
  return (
    <Routes>
      <Route element={<AppLayout />}>
        <Route index element={<Placeholder title="聊天" />} />
        <Route path="chat" element={<Placeholder title="聊天" />} />
        <Route path="admin/models" element={<Placeholder title="模型配置" />} />
        <Route path="admin/agents" element={<Placeholder title="Agent 配置" />} />
        <Route path="admin/skills" element={<Placeholder title="Skill 配置" />} />
        <Route path="admin/mcp" element={<Placeholder title="MCP 配置" />} />
        <Route path="admin/audit" element={<Placeholder title="Token 审计" />} />
        <Route path="*" element={<Placeholder title="页面不存在" />} />
      </Route>
    </Routes>
  );
}
