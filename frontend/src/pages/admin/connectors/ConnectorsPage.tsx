import { useCallback, useEffect, useMemo, useState } from "react";
import { toast } from "sonner";
import { Link2, Mail, Pencil, Plus, RefreshCw, ShieldAlert, Trash2 } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { listAgentDetails, type AgentDetail } from "@/api/agents";
import {
  bindConnectorAgents,
  deleteConnector,
  listConnectorActions,
  listConnectors,
  listConnectorTypes,
  testConnector,
  type ConnectorActionLog,
  type ConnectorInfo,
  type ConnectorTypeInfo,
} from "@/api/connectors";
import {
  ConnectorFormDialog,
  ConnectorStatusBadge,
  emptyConnectorForm,
  type ConnectorFormValue,
} from "@/components/admin/ConnectorFormDialog";

/**
 * 连接器页：类型卡片 + 实例列表 + 绑定 Agent + 写操作审计。
 *
 * <p>「归属用户」：每个人在这里只看得到自己的邮箱；把连接器绑定给某个 Agent 后，
 * 那个 Agent 才有对应的工具（`mail_list` / `mail_send`…）。
 */
function ConnectorsPage() {
  const [types, setTypes] = useState<ConnectorTypeInfo[]>([]);
  const [connectors, setConnectors] = useState<ConnectorInfo[]>([]);
  const [agents, setAgents] = useState<AgentDetail[]>([]);
  const [loading, setLoading] = useState(true);
  const [formOpen, setFormOpen] = useState(false);
  const [formValue, setFormValue] = useState<ConnectorFormValue | null>(null);
  const [binding, setBinding] = useState<ConnectorInfo | null>(null);
  const [bindingIds, setBindingIds] = useState<number[]>([]);
  const [audit, setAudit] = useState<{ connector: ConnectorInfo; rows: ConnectorActionLog[] } | null>(
    null,
  );
  const [testingId, setTestingId] = useState<number | null>(null);

  const load = useCallback(async () => {
    try {
      const [typeList, list] = await Promise.all([listConnectorTypes(), listConnectors()]);
      setTypes(typeList);
      setConnectors(list);
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "加载连接器失败");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
    listAgentDetails()
      .then((page) => setAgents(page.records ?? []))
      .catch(() => setAgents([]));
  }, [load]);

  const agentName = useMemo(() => {
    const map = new Map<number, string>();
    agents.forEach((agent) => map.set(agent.id, agent.name));
    return map;
  }, [agents]);

  const openCreate = (type: string) => {
    setFormValue(emptyConnectorForm(type));
    setFormOpen(true);
  };

  const openEdit = (connector: ConnectorInfo) => {
    setFormValue({
      id: connector.id,
      type: connector.type,
      name: connector.name,
      alias: connector.alias ?? "",
      enabled: connector.enabled,
      inboundMode: connector.inboundMode,
      // 密钥字段后端回显成 ***，原样带回去表示"不修改"
      config: { ...connector.config },
    });
    setFormOpen(true);
  };

  const runTest = async (connector: ConnectorInfo) => {
    setTestingId(connector.id);
    try {
      const result = await testConnector(connector.id);
      if (result.ok) {
        toast.success(`连接正常：${result.steps.join("；")}`);
      } else {
        toast.error(result.message);
      }
      await load();
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "测试失败");
    } finally {
      setTestingId(null);
    }
  };

  const saveBinding = async () => {
    if (!binding) {
      return;
    }
    try {
      await bindConnectorAgents(binding.id, bindingIds);
      toast.success("绑定已保存（下一轮对话生效）");
      setBinding(null);
      await load();
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "绑定失败");
    }
  };

  const openAudit = async (connector: ConnectorInfo) => {
    try {
      const rows = await listConnectorActions(connector.id);
      setAudit({ connector, rows });
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "加载审计失败");
    }
  };

  const remove = async (connector: ConnectorInfo) => {
    if (!window.confirm(`删除连接器「${connector.name}」？已绑定的 Agent 会立即失去这个能力。`)) {
      return;
    }
    try {
      await deleteConnector(connector.id);
      toast.success("已删除");
      await load();
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "删除失败");
    }
  };

  const toggle = (list: number[], id: number) =>
    list.includes(id) ? list.filter((item) => item !== id) : [...list, id];

  return (
    <div className="h-full overflow-y-auto p-6">
      <div className="mb-4 flex items-start justify-between gap-4">
        <div>
          <h2 className="text-lg font-semibold">连接器</h2>
          <p className="text-muted-foreground text-xs">
            让 Agent 使用你的外部账号（当前支持邮件：读信/搜索/下载附件/发信/回复）。
            连接器归属到你自己，绑定给某个 Agent 后，它才有对应能力。
          </p>
        </div>
        <Button variant="outline" size="sm" onClick={() => void load()} disabled={loading}>
          <RefreshCw className="mr-1 h-4 w-4" /> 刷新
        </Button>
      </div>

      <div className="mb-5 grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
        {types.map((type) => (
          <Card key={type.type} className={type.available ? "" : "opacity-60"}>
            <CardHeader className="pb-2">
              <CardTitle className="flex items-center gap-2 text-sm">
                <Mail className="h-4 w-4" /> {type.displayName}
                {type.available ? (
                  <Badge className="ml-auto bg-emerald-600">可用</Badge>
                ) : (
                  <Badge className="ml-auto" variant="secondary">
                    即将支持
                  </Badge>
                )}
              </CardTitle>
            </CardHeader>
            <CardContent className="space-y-2">
              <p className="text-muted-foreground text-xs">{type.description}</p>
              <div className="flex flex-wrap gap-1">
                {type.capabilities.map((capability) => (
                  <Badge key={capability} variant="outline" className="text-[10px]">
                    {capabilityLabel(capability)}
                  </Badge>
                ))}
              </div>
              <Button
                size="sm"
                className="w-full"
                disabled={!type.available}
                onClick={() => openCreate(type.type)}
              >
                <Plus className="mr-1 h-4 w-4" /> 添加
              </Button>
            </CardContent>
          </Card>
        ))}
      </div>

      <Card>
        <CardHeader className="pb-2">
          <CardTitle className="text-sm">我的连接器（{connectors.length}）</CardTitle>
        </CardHeader>
        <CardContent>
          {loading ? (
            <p className="text-muted-foreground text-sm">加载中…</p>
          ) : connectors.length === 0 ? (
            <p className="text-muted-foreground text-sm">
              还没有连接器。点上面的「添加」配一个邮箱，Agent 就能帮你读信、发信了。
            </p>
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>名称</TableHead>
                  <TableHead>账号</TableHead>
                  <TableHead>状态</TableHead>
                  <TableHead>新邮件</TableHead>
                  <TableHead>绑定 Agent</TableHead>
                  <TableHead className="text-right">操作</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {connectors.map((connector) => (
                  <TableRow key={connector.id}>
                    <TableCell className="font-medium">
                      {connector.name}
                      {connector.alias ? (
                        <span className="text-muted-foreground">（{connector.alias}）</span>
                      ) : null}
                      <div className="text-muted-foreground text-xs">{connector.typeName}</div>
                    </TableCell>
                    <TableCell className="text-sm">{connector.account || "-"}</TableCell>
                    <TableCell>
                      <ConnectorStatusBadge connector={connector} />
                      {connector.lastError ? (
                        <div className="text-destructive mt-1 max-w-[240px] text-xs">
                          {connector.lastError}
                        </div>
                      ) : null}
                    </TableCell>
                    <TableCell className="text-xs">{inboundLabel(connector.inboundMode)}</TableCell>
                    <TableCell className="text-xs">
                      {connector.agentIds.length === 0 ? (
                        <span className="text-muted-foreground">未绑定</span>
                      ) : (
                        connector.agentIds
                          .map((id) => agentName.get(id) ?? `#${id}`)
                          .join("、")
                      )}
                    </TableCell>
                    <TableCell className="text-right">
                      <div className="flex justify-end gap-1">
                        <Button
                          variant="ghost"
                          size="sm"
                          title="测试连接"
                          disabled={testingId === connector.id}
                          onClick={() => void runTest(connector)}
                        >
                          <RefreshCw
                            className={
                              testingId === connector.id
                                ? "h-4 w-4 animate-spin"
                                : "h-4 w-4"
                            }
                          />
                        </Button>
                        <Button
                          variant="ghost"
                          size="sm"
                          title="绑定 Agent"
                          onClick={() => {
                            setBinding(connector);
                            setBindingIds(connector.agentIds);
                          }}
                        >
                          <Link2 className="h-4 w-4" />
                        </Button>
                        <Button
                          variant="ghost"
                          size="sm"
                          title="写操作审计"
                          onClick={() => void openAudit(connector)}
                        >
                          <ShieldAlert className="h-4 w-4" />
                        </Button>
                        <Button variant="ghost" size="sm" title="编辑" onClick={() => openEdit(connector)}>
                          <Pencil className="h-4 w-4" />
                        </Button>
                        <Button
                          variant="ghost"
                          size="sm"
                          title="删除"
                          onClick={() => void remove(connector)}
                        >
                          <Trash2 className="h-4 w-4" />
                        </Button>
                      </div>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>

      <ConnectorFormDialog
        open={formOpen}
        types={types}
        initial={formValue}
        onClose={() => setFormOpen(false)}
        onSaved={() => void load()}
      />

      <Dialog open={binding != null} onOpenChange={(value) => !value && setBinding(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>绑定「{binding?.name}」到 Agent</DialogTitle>
            <DialogDescription>
              只有被绑定的 Agent 才会注册这个连接器的工具（下一轮对话生效）。
            </DialogDescription>
          </DialogHeader>
          <div className="max-h-64 space-y-2 overflow-y-auto">
            {agents.length === 0 ? (
              <p className="text-muted-foreground text-sm">还没有 Agent。</p>
            ) : (
              agents.map((agent) => (
                <label key={agent.id} className="flex items-center gap-2 text-sm">
                  <input
                    type="checkbox"
                    checked={bindingIds.includes(agent.id)}
                    onChange={() => setBindingIds(toggle(bindingIds, agent.id))}
                  />
                  {agent.name}
                  <span className="text-muted-foreground text-xs">（{agent.permissionMode}）</span>
                </label>
              ))
            )}
          </div>
          <DialogFooter>
            <Button variant="ghost" onClick={() => setBinding(null)}>
              取消
            </Button>
            <Button onClick={() => void saveBinding()}>保存绑定</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={audit != null} onOpenChange={(value) => !value && setAudit(null)}>
        <DialogContent className="max-h-[80vh] overflow-y-auto sm:max-w-2xl">
          <DialogHeader>
            <DialogTitle>「{audit?.connector.name}」写操作审计</DialogTitle>
            <DialogDescription>Agent 发信/回复/标记都会记在这里，发出去的东西能追溯。</DialogDescription>
          </DialogHeader>
          {!audit || audit.rows.length === 0 ? (
            <p className="text-muted-foreground text-sm">还没有写操作记录。</p>
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>动作</TableHead>
                  <TableHead>对象</TableHead>
                  <TableHead>主题</TableHead>
                  <TableHead>结果</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {audit.rows.map((row) => (
                  <TableRow key={row.id}>
                    <TableCell className="text-xs">{row.action}</TableCell>
                    <TableCell className="max-w-[200px] truncate text-xs">{row.target || "-"}</TableCell>
                    <TableCell className="max-w-[200px] truncate text-xs">{row.subject || "-"}</TableCell>
                    <TableCell className="text-xs">
                      {row.success ? (
                        <Badge className="bg-emerald-600">成功</Badge>
                      ) : (
                        <span className="text-destructive">{row.error || "失败"}</span>
                      )}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </DialogContent>
      </Dialog>
    </div>
  );
}

function capabilityLabel(capability: string) {
  switch (capability) {
    case "READ":
      return "读";
    case "SEARCH":
      return "搜索";
    case "ATTACHMENT":
      return "附件";
    case "SEND":
      return "发信";
    case "INBOUND":
      return "新邮件";
    default:
      return capability;
  }
}

function inboundLabel(mode: ConnectorInfo["inboundMode"]) {
  switch (mode) {
    case "NOTIFY":
      return "推 IM 通知";
    case "AUTOREPLY":
      return "Agent 自动回复";
    default:
      return "只记录";
  }
}

export default ConnectorsPage;
