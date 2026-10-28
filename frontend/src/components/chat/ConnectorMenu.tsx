import { useCallback, useEffect, useState } from "react";
import { Link } from "react-router";
import { toast } from "sonner";
import { Cable, RefreshCw } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { listConnectors, setConnectorEnabled, type ConnectorInfo } from "@/api/connectors";

/**
 * 输入框下方的「连接器」按钮：点开看**当前 Agent 能用的连接器**和它们的启用状态。
 *
 * 为什么放这儿：连接器是会话级能力的"开关"，用户在聊天时最常问的是
 * 「你到底能不能看我邮箱？」——把答案放在输入框旁边，比去管理台翻一层更直接。
 *
 * 数据来源：`/api/connectors` 只返回**当前用户自己的**连接器（服务端按属主过滤），
 * 这里再按 `agentIds` 过滤出绑定到当前 Agent 的那些，和工具注册口径保持一致。
 */
export function ConnectorMenu({ agentId }: { agentId: number | null }) {
  const [open, setOpen] = useState(false);
  const [connectors, setConnectors] = useState<ConnectorInfo[]>([]);
  const [loading, setLoading] = useState(false);
  const [busyId, setBusyId] = useState<number | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const all = await listConnectors();
      setConnectors(agentId == null ? [] : all.filter((item) => item.agentIds.includes(agentId)));
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "加载连接器失败");
    } finally {
      setLoading(false);
    }
  }, [agentId]);

  // 切换 Agent 就重新取：不同 Agent 绑定的连接器不一样
  useEffect(() => {
    void load();
  }, [load]);

  const enabledCount = connectors.filter((item) => item.enabled).length;

  const toggle = async (connector: ConnectorInfo, enabled: boolean) => {
    setBusyId(connector.id);
    // 先本地切换（开关手感要跟手），失败再回滚
    setConnectors((prev) =>
      prev.map((item) => (item.id === connector.id ? { ...item, enabled } : item)),
    );
    try {
      await setConnectorEnabled(connector.id, enabled);
      toast.success(enabled ? `已启用「${connector.name}」` : `已停用「${connector.name}」`);
    } catch (error) {
      setConnectors((prev) =>
        prev.map((item) => (item.id === connector.id ? { ...item, enabled: !enabled } : item)),
      );
      toast.error(error instanceof Error ? error.message : "切换失败");
    } finally {
      setBusyId(null);
    }
  };

  return (
    <Popover open={open} onOpenChange={(value) => {
      setOpen(value);
      if (value) {
        void load();
      }
    }}>
      <PopoverTrigger asChild>
        <Button
          variant="ghost"
          size="icon-sm"
          className="text-muted-foreground hover:text-foreground relative shrink-0 rounded-full"
          title="连接器（邮箱等外部账号）"
          data-testid="connector-menu-button"
        >
          <Cable className="size-4" />
          {enabledCount > 0 ? (
            <span className="bg-emerald-500 absolute -top-0.5 -right-0.5 size-2 rounded-full" />
          ) : null}
        </Button>
      </PopoverTrigger>
      <PopoverContent align="start" side="top" className="w-80">
        <div className="mb-2 flex items-center justify-between">
          <span className="text-sm font-medium">连接器</span>
          <Button
            variant="ghost"
            size="icon-sm"
            title="刷新"
            onClick={() => void load()}
            disabled={loading}
          >
            <RefreshCw className={loading ? "size-3.5 animate-spin" : "size-3.5"} />
          </Button>
        </div>

        {agentId == null ? (
          <p className="text-muted-foreground text-xs">先选择一个 Agent。</p>
        ) : connectors.length === 0 ? (
          <div className="space-y-2">
            <p className="text-muted-foreground text-xs">
              当前 Agent 还没有绑定连接器（例如邮箱）。绑定后它才能读信、发信。
            </p>
            <Link
              to="/admin/capabilities/connectors"
              className="text-primary text-xs underline underline-offset-2"
              onClick={() => setOpen(false)}
            >
              去「能力 → 连接器」添加并绑定
            </Link>
          </div>
        ) : (
          <ul className="space-y-2">
            {connectors.map((connector) => (
              <li key={connector.id} className="rounded-md border p-2">
                <div className="flex items-start justify-between gap-2">
                  <div className="min-w-0">
                    <div className="flex items-center gap-1 text-sm font-medium">
                      <span className="truncate">{connector.name}</span>
                      {connector.alias ? (
                        <span className="text-muted-foreground text-xs">（{connector.alias}）</span>
                      ) : null}
                      <StatusDot connector={connector} />
                    </div>
                    <div className="text-muted-foreground truncate text-xs">
                      {connector.account || connector.typeName}
                    </div>
                    {connector.lastError && connector.enabled ? (
                      <div className="text-destructive mt-1 line-clamp-2 text-xs">
                        {connector.lastError}
                      </div>
                    ) : null}
                  </div>
                  <label className="flex shrink-0 cursor-pointer items-center gap-1 text-xs">
                    <input
                      type="checkbox"
                      checked={connector.enabled}
                      disabled={busyId === connector.id}
                      onChange={(event) => void toggle(connector, event.target.checked)}
                      aria-label={`启用 ${connector.name}`}
                    />
                    {connector.enabled ? "启用" : "停用"}
                  </label>
                </div>
              </li>
            ))}
          </ul>
        )}

        {connectors.length > 0 ? (
          <div className="mt-2 flex items-center justify-between">
            <span className="text-muted-foreground text-xs">
              已启用 {enabledCount}/{connectors.length}
            </span>
            <Link
              to="/admin/capabilities/connectors"
              className="text-primary text-xs underline underline-offset-2"
              onClick={() => setOpen(false)}
            >
              管理
            </Link>
          </div>
        ) : null}
      </PopoverContent>
    </Popover>
  );
}

/** 状态点：正常绿、异常红、未测试灰（停用则整体灰）。 */
function StatusDot({ connector }: { connector: ConnectorInfo }) {
  if (!connector.enabled) {
    return <Badge variant="secondary" className="px-1 py-0 text-[10px]">已停用</Badge>;
  }
  if (connector.status === "OK") {
    return <Badge className="bg-emerald-600 px-1 py-0 text-[10px]">正常</Badge>;
  }
  if (connector.status === "ERROR") {
    return <Badge variant="destructive" className="px-1 py-0 text-[10px]">异常</Badge>;
  }
  return <Badge variant="outline" className="px-1 py-0 text-[10px]">未测试</Badge>;
}
