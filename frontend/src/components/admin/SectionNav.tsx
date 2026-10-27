import { Suspense } from "react";
import { NavLink, Outlet } from "react-router";
import type { LucideIcon } from "lucide-react";
import { cn } from "@/lib/utils";

/**
 * 管理台「多页合一」的公共外壳。
 *
 * 背景：原来侧边栏是 8 个平铺的管理条目（模型 / Agent / Skill / MCP / IM 渠道 / 定时 / 审计 / 用户），
 * 越加越长且同族功能被拆散。现在把同族页面收进一个主菜单，用**路由驱动的 tab** 区分。
 *
 * 为什么用 NavLink 而不是 Radix Tabs：
 * 1) tab 状态落在 URL 上，可分享、可前进后退、刷新不丢（Tabs 用 state 做不到）；
 * 2) 页面组件本身不用改一行——它们仍是各自独立的整页（自带 h-full overflow-auto）。
 *
 * Suspense 放在外壳内部、只包 Outlet：切换 tab 时 tab 栏保持不动，只有内容区显示"加载中"。
 * （AppLayout 里也有 Suspense，但那个会把整个 tab 外壳一起替换掉。）
 */

export interface SectionTab {
  /** 相对该 section 根路径的子路径，如 "agents" */
  to: string;
  label: string;
  icon?: LucideIcon;
  /** 悬停提示，用于解释这个 tab 里到底是什么 */
  hint?: string;
}

function Loading() {
  return (
    <div className="text-muted-foreground flex h-40 items-center justify-center text-sm">
      加载中…
    </div>
  );
}

/** 顶部横向 tab 的外壳（用于「Agent 与模型」「技能与 MCP」）。 */
export function TabbedSection({
  title,
  description,
  tabs,
}: {
  title: string;
  description?: string;
  tabs: SectionTab[];
}) {
  return (
    <div className="flex h-full flex-col overflow-hidden">
      <div className="shrink-0 border-b px-6 pt-4">
        <div>
          <h1 className="text-xl font-semibold">{title}</h1>
          {description ? (
            <p className="text-muted-foreground mt-0.5 text-xs">{description}</p>
          ) : null}
        </div>
        <nav className="mt-3 flex gap-1" role="tablist" aria-label={`${title} 分区`}>
          {tabs.map((tab) => (
            <NavLink
              key={tab.to}
              to={tab.to}
              role="tab"
              title={tab.hint}
              className={({ isActive }) =>
                cn(
                  "-mb-px flex items-center gap-1.5 border-b-2 px-3 py-2 text-sm transition-colors",
                  isActive
                    ? "border-primary text-foreground font-medium"
                    : "text-muted-foreground hover:text-foreground border-transparent",
                )
              }
            >
              {tab.icon ? <tab.icon className="size-4" /> : null}
              {tab.label}
            </NavLink>
          ))}
        </nav>
      </div>
      <div className="min-h-0 flex-1">
        <Suspense fallback={<Loading />}>
          <Outlet />
        </Suspense>
      </div>
    </div>
  );
}

/** 左侧二级菜单的外壳（用于「系统设置」，条目后续还会增加）。 */
export function SidebarSection({
  title,
  description,
  tabs,
  footer,
}: {
  title: string;
  description?: string;
  tabs: SectionTab[];
  /** 二级菜单底部的说明/预留位（例如"后续还会加更多设置"） */
  footer?: React.ReactNode;
}) {
  return (
    <div className="flex h-full flex-col overflow-hidden">
      <div className="shrink-0 border-b px-6 pt-4 pb-3">
        <h1 className="text-xl font-semibold">{title}</h1>
        {description ? (
          <p className="text-muted-foreground mt-0.5 text-xs">{description}</p>
        ) : null}
      </div>
      <div className="flex min-h-0 flex-1">
        <nav
          className="bg-muted/30 flex w-44 shrink-0 flex-col gap-1 overflow-y-auto border-r p-2"
          aria-label={`${title} 子菜单`}
        >
          {tabs.map((tab) => (
            <NavLink
              key={tab.to}
              to={tab.to}
              title={tab.hint}
              className={({ isActive }) =>
                cn(
                  "flex items-center gap-2 rounded-md px-3 py-2 text-sm transition-colors",
                  isActive
                    ? "bg-background text-foreground font-medium shadow-sm"
                    : "text-muted-foreground hover:bg-background/60 hover:text-foreground",
                )
              }
            >
              {tab.icon ? <tab.icon className="size-4" /> : null}
              {tab.label}
            </NavLink>
          ))}
          {footer ? (
            <div className="text-muted-foreground mt-auto px-3 pt-3 text-[11px] leading-relaxed">
              {footer}
            </div>
          ) : null}
        </nav>
        <div className="min-w-0 flex-1">
          <Suspense fallback={<Loading />}>
            <Outlet />
          </Suspense>
        </div>
      </div>
    </div>
  );
}
