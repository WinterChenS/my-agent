import { useCallback, useEffect, useState } from "react";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Badge } from "@/components/ui/badge";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import {
  fetchTokenRecords,
  fetchTokenSummary,
  fetchTokenUsers,
  type TokenRecord,
  type TokenSummary,
  type TokenUser,
} from "@/api/audit";
import { useAuthStore } from "@/stores/auth";

/** 按日趋势：用内联 SVG 条形绘制，不引入图表库（方案要求不超过 1 个）。 */
function DailyTrend({ rows }: { rows: TokenSummary["byDay"] }) {
  if (!rows || rows.length === 0) {
    return <p className="text-muted-foreground text-sm">暂无数据</p>;
  }
  const max = Math.max(...rows.map((r) => Number(r.total_tokens) || 0), 1);
  const barWidth = 28;
  const gap = 10;
  const height = 120;
  const width = rows.length * (barWidth + gap) + gap;

  return (
    <div className="overflow-auto">
      <svg width={width} height={height + 24} role="img" aria-label="按日 Token 趋势">
        {rows.map((row, index) => {
          const value = Number(row.total_tokens) || 0;
          const barHeight = Math.max(2, Math.round((value / max) * height));
          const x = gap + index * (barWidth + gap);
          const y = height - barHeight;
          return (
            <g key={`${row.day}-${index}`}>
              <rect x={x} y={y} width={barWidth} height={barHeight} className="fill-primary" rx={3} />
              <text x={x + barWidth / 2} y={height + 14} textAnchor="middle" className="fill-muted-foreground text-[9px]">
                {String(row.day ?? "").slice(5)}
              </text>
              <title>{`${row.day}: ${value} tokens`}</title>
            </g>
          );
        })}
      </svg>
    </div>
  );
}

export default function AuditPage() {
  const user = useAuthStore((s) => s.user);
  const isAdmin = user?.role === "ADMIN";

  const [users, setUsers] = useState<TokenUser[]>([]);
  const [selectedUser, setSelectedUser] = useState<number | null>(null);
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [summary, setSummary] = useState<TokenSummary | null>(null);
  const [records, setRecords] = useState<TokenRecord[]>([]);
  const [total, setTotal] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [summaryData, page] = await Promise.all([
        fetchTokenSummary({
          userId: selectedUser ?? undefined,
          from: from ? `${from}T00:00:00Z` : undefined,
          to: to ? `${to}T23:59:59Z` : undefined,
        }),
        fetchTokenRecords({
          userId: selectedUser ?? undefined,
          from: from ? `${from}T00:00:00Z` : undefined,
          to: to ? `${to}T23:59:59Z` : undefined,
          current: 1,
          size: 20,
        }),
      ]);
      setSummary(summaryData);
      setRecords(page.records);
      setTotal(page.total);
    } catch (err) {
      setError(err instanceof Error ? err.message : "加载审计数据失败");
    } finally {
      setLoading(false);
    }
  }, [selectedUser, from, to]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    fetchTokenUsers()
      .then(setUsers)
      .catch(() => {
        /* 普通用户也可能没有数据 */
      });
  }, []);

  return (
    <div className="h-full space-y-4 overflow-auto p-6">
      <div className="flex flex-wrap items-end gap-3">
        <div>
          <div className="text-sm font-semibold">Token 审计</div>
          <p className="text-muted-foreground text-xs">
            {isAdmin ? "管理员可见全员用量" : "仅可见本人用量"}
          </p>
        </div>

        <div className="ml-auto flex flex-wrap items-end gap-2">
          <div className="space-y-1">
            <label className="text-xs" htmlFor="audit-user">
              用户
            </label>
            <select
              id="audit-user"
              className="border-input h-9 rounded-md border bg-transparent px-2 text-sm"
              value={selectedUser ?? ""}
              onChange={(e) => setSelectedUser(e.target.value ? Number(e.target.value) : null)}
            >
              <option value="">{isAdmin ? "全部（默认本人）" : "本人"}</option>
              {users.map((u) => (
                <option key={u.user_id} value={u.user_id}>
                  {u.display_name ?? u.username}
                </option>
              ))}
            </select>
          </div>
          <div className="space-y-1">
            <label className="text-xs" htmlFor="audit-from">
              起始日期
            </label>
            <Input id="audit-from" type="date" value={from} onChange={(e) => setFrom(e.target.value)} />
          </div>
          <div className="space-y-1">
            <label className="text-xs" htmlFor="audit-to">
              结束日期
            </label>
            <Input id="audit-to" type="date" value={to} onChange={(e) => setTo(e.target.value)} />
          </div>
          <Button onClick={() => void load()} disabled={loading}>
            {loading ? "查询中…" : "查询"}
          </Button>
        </div>
      </div>

      {error && <div className="border-destructive/40 bg-destructive/10 rounded-md border p-2 text-sm">{error}</div>}

      <div className="grid gap-3 sm:grid-cols-3">
        <Card>
          <CardHeader className="p-3">
            <CardTitle className="text-xs font-normal">输入 Tokens</CardTitle>
          </CardHeader>
          <CardContent className="p-3 pt-0 text-2xl font-semibold">
            {summary?.promptTokens ?? 0}
          </CardContent>
        </Card>
        <Card>
          <CardHeader className="p-3">
            <CardTitle className="text-xs font-normal">输出 Tokens</CardTitle>
          </CardHeader>
          <CardContent className="p-3 pt-0 text-2xl font-semibold">
            {summary?.completionTokens ?? 0}
          </CardContent>
        </Card>
        <Card>
          <CardHeader className="p-3">
            <CardTitle className="text-xs font-normal">合计 Tokens</CardTitle>
          </CardHeader>
          <CardContent className="p-3 pt-0 text-2xl font-semibold">
            {summary?.totalTokens ?? 0}
            <span className="text-muted-foreground ml-2 text-xs font-normal">
              用户 #{summary?.userId ?? "-"}
            </span>
          </CardContent>
        </Card>
      </div>

      <Card>
        <CardHeader className="p-3">
          <CardTitle className="text-sm">按日趋势</CardTitle>
        </CardHeader>
        <CardContent className="p-3 pt-0">
          <DailyTrend rows={summary?.byDay ?? []} />
        </CardContent>
      </Card>

      <Card>
        <CardHeader className="p-3">
          <CardTitle className="text-sm">按模型分布</CardTitle>
        </CardHeader>
        <CardContent className="p-3 pt-0">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>模型 ID</TableHead>
                <TableHead>输入</TableHead>
                <TableHead>输出</TableHead>
                <TableHead>合计</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {(summary?.byModel ?? []).map((row, index) => (
                <TableRow key={`${row.model_id}-${index}`}>
                  <TableCell>{row.model_id ?? "-"}</TableCell>
                  <TableCell>{row.prompt_tokens}</TableCell>
                  <TableCell>{row.completion_tokens}</TableCell>
                  <TableCell>{row.total_tokens}</TableCell>
                </TableRow>
              ))}
              {(summary?.byModel ?? []).length === 0 && (
                <TableRow>
                  <TableCell colSpan={4} className="text-muted-foreground text-center text-sm">
                    暂无数据
                  </TableCell>
                </TableRow>
              )}
            </TableBody>
          </Table>
        </CardContent>
      </Card>

      <Card>
        <CardHeader className="flex-row items-center justify-between p-3">
          <CardTitle className="text-sm">用量明细</CardTitle>
          <Badge variant="secondary">共 {total} 条</Badge>
        </CardHeader>
        <CardContent className="p-3 pt-0">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>时间</TableHead>
                <TableHead>请求</TableHead>
                <TableHead>会话</TableHead>
                <TableHead>模型</TableHead>
                <TableHead>输入</TableHead>
                <TableHead>输出</TableHead>
                <TableHead>合计</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {records.map((row) => (
                <TableRow key={row.id}>
                  <TableCell className="text-xs">
                    {row.createdAt ? new Date(row.createdAt).toLocaleString() : "-"}
                  </TableCell>
                  <TableCell className="font-mono text-xs">{row.requestId ?? "-"}</TableCell>
                  <TableCell>{row.sessionId ?? "-"}</TableCell>
                  <TableCell>{row.modelId ?? "-"}</TableCell>
                  <TableCell>{row.promptTokens}</TableCell>
                  <TableCell>{row.completionTokens}</TableCell>
                  <TableCell>{row.totalTokens}</TableCell>
                </TableRow>
              ))}
              {records.length === 0 && (
                <TableRow>
                  <TableCell colSpan={7} className="text-muted-foreground text-center text-sm">
                    暂无数据
                  </TableCell>
                </TableRow>
              )}
            </TableBody>
          </Table>
        </CardContent>
      </Card>
    </div>
  );
}
