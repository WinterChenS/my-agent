import { useCallback, useEffect, useState } from "react";
import { toast } from "sonner";
import { Plus, RefreshCw, Trash2, Pencil, Wrench } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import {
  MCP_TRANSPORTS,
  createMcpServer,
  deleteMcpServer,
  listMcpServers,
  syncMcpTools,
  updateMcpServer,
  type McpServerInfo,
  type McpServerPayload,
  type McpToolCacheEntry,
  type McpTransport,
} from "@/api/mcp";
import { listTools, type ToolEntry } from "@/api/tools";

/** 每行一个 KEY=VALUE。 */
function linesToMap(text: string): Record<string, string> {
  const out: Record<string, string> = {};
  text
    .split("\n")
    .map((line) => line.trim())
    .filter(Boolean)
    .forEach((line) => {
      const idx = line.indexOf("=");
      if (idx > 0) {
        out[line.slice(0, idx).trim()] = line.slice(idx + 1).trim();
      }
    });
  return out;
}

function mapToLines(map: Record<string, string> | null | undefined): string {
  if (!map) return "";
  return Object.entries(map)
    .map(([k, v]) => `${k}=${v}`)
    .join("\n");
}

interface FormState {
  id: number | null;
  name: string;
  transport: McpTransport;
  command: string;
  argsText: string;
  endpointUrl: string;
  envText: string;
  headersText: string;
  status: "ENABLED" | "DISABLED";
}

const EMPTY_FORM: FormState = {
  id: null,
  name: "",
  transport: "STDIO",
  command: "",
  argsText: "",
  endpointUrl: "",
  envText: "",
  headersText: "",
  status: "ENABLED",
};

export default function McpPage() {
  const [servers, setServers] = useState<McpServerInfo[]>([]);
  const [loading, setLoading] = useState(false);
  const [busyId, setBusyId] = useState<number | null>(null);
  const [dialogOpen, setDialogOpen] = useState(false);
  const [form, setForm] = useState<FormState>(EMPTY_FORM);
  const [tools, setTools] = useState<Record<number, McpToolCacheEntry[]>>({});
  /** 平台内置（框架注册）工具：只读展示。它们不属于任何 MCP 服务器，也不由平台管理。 */
  const [builtinTools, setBuiltinTools] = useState<ToolEntry[]>([]);
  // 分页 + 筛选（MCP 服务器一多，一页刷到底根本找不到）
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(10);
  const [total, setTotal] = useState(0);
  const [keyword, setKeyword] = useState("");
  const [statusFilter, setStatusFilter] = useState<"" | "ENABLED" | "DISABLED">("");
  const [transportFilter, setTransportFilter] = useState<"" | McpTransport>("");
  /** 已提交生效的筛选（输入框改动不立即请求，点「查询」才生效） */
  const [applied, setApplied] = useState({ keyword: "", status: "" as "" | "ENABLED" | "DISABLED", transport: "" as "" | McpTransport });

  const refresh = useCallback(async () => {
    setLoading(true);
    try {
      const result = await listMcpServers({
        current: page,
        size: pageSize,
        keyword: applied.keyword,
        status: applied.status,
        transport: applied.transport,
      });
      setServers(result.records);
      setTotal(result.total);
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "加载 MCP 列表失败");
    } finally {
      setLoading(false);
    }
    // 依赖必须带上 page/pageSize/applied：否则翻页与筛选变了但 refresh 身份不变，
    // 下面的 effect 不会重跑 → 表格看着像卡住了（这类 bug lint 会直接提出来）。
  }, [page, pageSize, applied]);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  // 内置工具清单（只读）：来自框架注册的 Toolkit，与 MCP 无关，整个页面生命周期只拉一次
  useEffect(() => {
    void (async () => {
      try {
        const all = await listTools();
        setBuiltinTools(all.filter((t) => t.group.includes("本地")));
      } catch {
        setBuiltinTools([]);
      }
    })();
  }, []);

  function openCreate() {
    setForm(EMPTY_FORM);
    setDialogOpen(true);
  }

  function openEdit(server: McpServerInfo) {
    setForm({
      id: server.id,
      name: server.name,
      transport: server.transport,
      command: server.command ?? "",
      argsText: (server.args ?? []).join("\n"),
      endpointUrl: server.endpointUrl ?? "",
      envText: mapToLines(server.env),
      headersText: mapToLines(server.headers),
      status: server.status,
    });
    setDialogOpen(true);
  }

  async function save() {
    const payload: McpServerPayload = {
      name: form.name.trim(),
      transport: form.transport,
      command: form.transport === "STDIO" ? form.command.trim() : null,
      args: form.transport === "STDIO"
        ? form.argsText.split("\n").map((s) => s.trim()).filter(Boolean)
        : [],
      endpointUrl: form.transport === "STDIO" ? null : form.endpointUrl.trim(),
      env: linesToMap(form.envText),
      headers: linesToMap(form.headersText),
      status: form.status,
    };
    if (!payload.name) {
      toast.error("名称不能为空");
      return;
    }
    try {
      if (form.id == null) {
        await createMcpServer(payload);
        toast.success("已创建 MCP Server");
      } else {
        await updateMcpServer(form.id, payload);
        toast.success("已保存");
      }
      setDialogOpen(false);
      await refresh();
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "保存失败");
    }
  }

  async function remove(server: McpServerInfo) {
    if (!confirm(`删除 MCP Server「${server.name}」？`)) return;
    try {
      await deleteMcpServer(server.id);
      toast.success("已删除");
      await refresh();
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "删除失败");
    }
  }

  async function sync(server: McpServerInfo) {
    setBusyId(server.id);
    try {
      const list = await syncMcpTools(server.id);
      setTools((prev) => ({ ...prev, [server.id]: list }));
      toast.success(`同步成功：${list.length} 个工具`);
      await refresh();
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "同步失败");
    } finally {
      setBusyId(null);
    }
  }

  return (
    // h-full + overflow-auto：外层 <main> 是 overflow-hidden，页面必须自己当滚动容器，
    // 否则内容比视口高时会被直接裁掉、滑不到底（MCP 页加了筛选+分页+内置工具后暴露）。
    // 与 AuditPage 保持一致。
    <div className="h-full space-y-4 overflow-auto p-6">
      <div className="flex items-center gap-3">
        <div>
          <h1 className="text-xl font-semibold">MCP 配置</h1>
          <p className="text-muted-foreground text-xs">
            支持 STDIO / SSE / Streamable HTTP 三种传输；同步工具后可绑定到 Agent。
          </p>
        </div>
        <Button className="ml-auto" onClick={openCreate}>
          <Plus className="size-4" /> 新建
        </Button>
        <Button variant="outline" onClick={() => void refresh()} disabled={loading}>
          <RefreshCw className="size-4" /> 刷新
        </Button>
      </div>

      <Card>
        <CardHeader className="p-3">
          <CardTitle className="text-sm">Server 列表（{total}）</CardTitle>
        </CardHeader>
        <CardContent className="p-3 pt-0 space-y-3">
          {/* 筛选条 */}
          <div className="flex flex-wrap items-end gap-2" data-testid="mcp-filters">
            <label className="space-y-1 text-xs">
              名称包含
              <Input
                className="h-8 w-48"
                data-testid="mcp-filter-keyword"
                placeholder="如 everything"
                value={keyword}
                onChange={(e) => setKeyword(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === "Enter") {
                    setPage(1);
                    setApplied({ keyword, status: statusFilter, transport: transportFilter });
                  }
                }}
              />
            </label>
            <label className="space-y-1 text-xs">
              状态
              <select
                className="border-input h-8 rounded-md border bg-transparent px-2 text-xs"
                data-testid="mcp-filter-status"
                value={statusFilter}
                onChange={(e) => setStatusFilter(e.target.value as "" | "ENABLED" | "DISABLED")}
              >
                <option value="">全部</option>
                <option value="ENABLED">ENABLED</option>
                <option value="DISABLED">DISABLED</option>
              </select>
            </label>
            <label className="space-y-1 text-xs">
              传输
              <select
                className="border-input h-8 rounded-md border bg-transparent px-2 text-xs"
                data-testid="mcp-filter-transport"
                value={transportFilter}
                onChange={(e) => setTransportFilter(e.target.value as "" | McpTransport)}
              >
                <option value="">全部</option>
                {MCP_TRANSPORTS.map((t) => (
                  <option key={t} value={t}>
                    {t}
                  </option>
                ))}
              </select>
            </label>
            <Button
              size="sm"
              data-testid="mcp-filter-apply"
              onClick={() => {
                setPage(1);
                setApplied({ keyword, status: statusFilter, transport: transportFilter });
              }}
            >
              查询
            </Button>
            <Button
              size="sm"
              variant="outline"
              data-testid="mcp-filter-reset"
              onClick={() => {
                setKeyword("");
                setStatusFilter("");
                setTransportFilter("");
                setPage(1);
                setApplied({ keyword: "", status: "", transport: "" });
              }}
            >
              重置
            </Button>
          </div>

          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>名称</TableHead>
                <TableHead>传输</TableHead>
                <TableHead>目标</TableHead>
                <TableHead>工具</TableHead>
                <TableHead>最近同步</TableHead>
                <TableHead>状态</TableHead>
                <TableHead className="text-right">操作</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {servers.map((s) => {
                const list = tools[s.id] ?? s.toolCache ?? [];
                return (
                  <TableRow key={s.id}>
                    <TableCell className="font-medium">{s.name}</TableCell>
                    <TableCell>
                      <Badge variant="outline">{s.transport}</Badge>
                    </TableCell>
                    <TableCell className="max-w-[240px] truncate font-mono text-xs">
                      {s.transport === "STDIO" ? s.command : s.endpointUrl}
                    </TableCell>
                    <TableCell>
                      {list.length > 0 ? (
                        <span title={list.map((t) => t.name).join(", ")}>
                          {list.length} 个
                        </span>
                      ) : (
                        <span className="text-muted-foreground text-xs">未同步</span>
                      )}
                    </TableCell>
                    <TableCell className="text-xs">
                      {s.lastSyncedAt ? new Date(s.lastSyncedAt).toLocaleString() : "-"}
                    </TableCell>
                    <TableCell>
                      <Badge variant={s.status === "ENABLED" ? "secondary" : "outline"}>
                        {s.status}
                      </Badge>
                    </TableCell>
                    <TableCell className="text-right">
                      <div className="flex justify-end gap-1">
                        <Button
                          size="sm"
                          variant="outline"
                          disabled={busyId === s.id}
                          onClick={() => void sync(s)}
                          title="同步工具"
                        >
                          <Wrench className="size-3.5" />
                          {busyId === s.id ? "同步中…" : "同步工具"}
                        </Button>
                        <Button size="icon" variant="ghost" onClick={() => openEdit(s)} title="编辑">
                          <Pencil className="size-3.5" />
                        </Button>
                        <Button
                          size="icon"
                          variant="ghost"
                          onClick={() => void remove(s)}
                          title="删除"
                        >
                          <Trash2 className="size-3.5" />
                        </Button>
                      </div>
                    </TableCell>
                  </TableRow>
                );
              })}
              {servers.length === 0 && (
                <TableRow>
                  <TableCell colSpan={7} className="text-muted-foreground text-center text-sm">
                    还没有 MCP Server，点右上角「新建」。
                  </TableCell>
                </TableRow>
              )}
            </TableBody>
          </Table>

          {/* 分页 */}
          <div
            className="mt-3 flex flex-wrap items-center justify-between gap-2 text-sm"
            data-testid="mcp-pagination"
          >
            <div className="text-muted-foreground text-xs">
              共 {total} 条，第 {page} / {Math.max(1, Math.ceil(total / pageSize))} 页
            </div>
            <div className="flex items-center gap-2">
              <label className="text-muted-foreground text-xs">
                每页
                <select
                  className="border-input ml-1 h-7 rounded-md border px-1 text-xs"
                  data-testid="mcp-page-size"
                  value={pageSize}
                  onChange={(e) => {
                    setPageSize(Number(e.target.value));
                    setPage(1);
                  }}
                >
                  {[10, 20, 50].map((n) => (
                    <option key={n} value={n}>
                      {n}
                    </option>
                  ))}
                </select>
              </label>
              <Button
                variant="outline"
                size="sm"
                data-testid="mcp-prev"
                disabled={page <= 1 || loading}
                onClick={() => setPage((p) => Math.max(1, p - 1))}
              >
                上一页
              </Button>
              <Button
                variant="outline"
                size="sm"
                data-testid="mcp-next"
                disabled={page >= Math.ceil(total / pageSize) || loading}
                onClick={() => setPage((p) => p + 1)}
              >
                下一页
              </Button>
            </div>
          </div>
        </CardContent>
      </Card>

      {/* 平台内置工具（只读）——它们不出现在任何 MCP 服务器里，但 Agent 本来就能调 */}
      <Card data-testid="builtin-tools-card">
        <CardHeader className="p-3">
          <CardTitle className="text-sm">
            本地工具（内置，共 {builtinTools.length}）
            <span className="text-muted-foreground ml-2 text-xs font-normal">
              由 AgentScope 框架 / 平台注册，不属于任何 MCP 服务器、无需配置即可被 Agent 调用（只读展示）
            </span>
          </CardTitle>
        </CardHeader>
        <CardContent className="p-3 pt-0">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>工具名</TableHead>
                <TableHead>说明</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {builtinTools.map((t) => (
                <TableRow key={t.name} data-testid="builtin-tool-row">
                  <TableCell className="font-mono text-xs">{t.name}</TableCell>
                  <TableCell className="max-w-[520px] truncate text-xs">{t.hint ?? ""}</TableCell>
                </TableRow>
              ))}
              {builtinTools.length === 0 && (
                <TableRow>
                  <TableCell colSpan={2} className="text-muted-foreground text-center text-sm">
                    未获取到内置工具（后端未就绪时会为空）。
                  </TableCell>
                </TableRow>
              )}
            </TableBody>
          </Table>
        </CardContent>
      </Card>

      {/* 工具清单 */}
      {Object.entries(tools).map(([id, list]) => {
        const server = servers.find((s) => s.id === Number(id));
        return (
          <Card key={id}>
            <CardHeader className="p-3">
              <CardTitle className="text-sm">
                工具清单：{server?.name ?? id}（{list.length}）
              </CardTitle>
            </CardHeader>
            <CardContent className="p-3 pt-0">
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>工具名</TableHead>
                    <TableHead>说明</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {list.map((t) => (
                    <TableRow key={t.name}>
                      <TableCell className="font-mono text-xs">{t.name}</TableCell>
                      <TableCell className="text-xs">{t.description ?? ""}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </CardContent>
          </Card>
        );
      })}

      <Dialog open={dialogOpen} onOpenChange={setDialogOpen}>
        <DialogContent className="max-w-2xl">
          <DialogHeader>
            <DialogTitle>{form.id == null ? "新建 MCP Server" : "编辑 MCP Server"}</DialogTitle>
          </DialogHeader>
          <div className="grid gap-3 sm:grid-cols-2">
            <label className="space-y-1 text-sm">
              名称
              <Input
                value={form.name}
                onChange={(e) => setForm({ ...form, name: e.target.value })}
                placeholder="mcp-everything"
              />
            </label>
            <label className="space-y-1 text-sm">
              传输
              <select
                className="border-input h-9 w-full rounded-md border bg-transparent px-2 text-sm"
                value={form.transport}
                onChange={(e) => setForm({ ...form, transport: e.target.value as McpTransport })}
              >
                {MCP_TRANSPORTS.map((t) => (
                  <option key={t} value={t}>
                    {t}
                  </option>
                ))}
              </select>
            </label>

            {form.transport === "STDIO" ? (
              <>
                <label className="space-y-1 text-sm">
                  命令
                  <Input
                    value={form.command}
                    onChange={(e) => setForm({ ...form, command: e.target.value })}
                    placeholder="cmd" 
                  />
                </label>
                <label className="space-y-1 text-sm">
                  参数（每行一个）
                  <textarea
                    className="border-input min-h-[72px] w-full rounded-md border bg-transparent p-2 font-mono text-xs"
                    value={form.argsText}
                    onChange={(e) => setForm({ ...form, argsText: e.target.value })}
                    placeholder={"/c\nnpx\n-y\n@modelcontextprotocol/server-everything\nstdio"}
                  />
                </label>
              </>
            ) : (
              <label className="space-y-1 text-sm sm:col-span-2">
                地址
                <Input
                  value={form.endpointUrl}
                  onChange={(e) => setForm({ ...form, endpointUrl: e.target.value })}
                  placeholder="http://localhost:3001/sse"
                />
              </label>
            )}

            <label className="space-y-1 text-sm">
              环境变量（每行 KEY=VALUE）
              <textarea
                className="border-input min-h-[72px] w-full rounded-md border bg-transparent p-2 font-mono text-xs"
                value={form.envText}
                onChange={(e) => setForm({ ...form, envText: e.target.value })}
              />
            </label>
            <label className="space-y-1 text-sm">
              请求头（每行 KEY=VALUE）
              <textarea
                className="border-input min-h-[72px] w-full rounded-md border bg-transparent p-2 font-mono text-xs"
                value={form.headersText}
                onChange={(e) => setForm({ ...form, headersText: e.target.value })}
              />
            </label>
            <label className="space-y-1 text-sm">
              状态
              <select
                className="border-input h-9 w-full rounded-md border bg-transparent px-2 text-sm"
                value={form.status}
                onChange={(e) =>
                  setForm({ ...form, status: e.target.value as "ENABLED" | "DISABLED" })
                }
              >
                <option value="ENABLED">ENABLED</option>
                <option value="DISABLED">DISABLED</option>
              </select>
            </label>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setDialogOpen(false)}>
              取消
            </Button>
            <Button onClick={() => void save()}>保存</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
