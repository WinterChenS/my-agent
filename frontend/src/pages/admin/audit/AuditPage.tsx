import { useCallback, useEffect, useRef, useState } from "react";
import { getUserQuota, setUserQuota, type QuotaStatus } from "@/api/quota";
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
import { money } from "@/lib/utils";
import {
  BreakdownBars,
  Donut,
  DailyTrendChart,
  fmt,
} from "@/components/admin/AuditCharts";

export default function AuditPage() {
  const user = useAuthStore((s) => s.user);
  const isAdmin = user?.role === "ADMIN";

  const [users, setUsers] = useState<TokenUser[]>([]);
  const [selectedUser, setSelectedUser] = useState<number | null>(null);
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [summary, setSummary] = useState<TokenSummary | null>(null);
  // tier1-upgrades ④：额度（显示已用/额度，并可设置）
  const [quota, setQuota] = useState<QuotaStatus | null>(null);
  const [quotaDaily, setQuotaDaily] = useState("");
  const [quotaMonthly, setQuotaMonthly] = useState("");
  const [quotaMode, setQuotaMode] = useState<"BLOCK" | "WARN">("BLOCK");
  const [quotaNotice, setQuotaNotice] = useState<string | null>(null);
  const [records, setRecords] = useState<TokenRecord[]>([]);
  const [total, setTotal] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  /** 输入框里当前这份数值属于哪个用户（用来区分「换用户」和「同一用户的后台重拉」）。 */
  const quotaUserRef = useRef<number | null>(null);
  /** 额度请求序号：快速切用户时，先发的响应可能后到，用它把过期响应丢掉。 */
  const quotaReqRef = useRef(0);

  /** 拉该用户的额度状态（审计看板显示已用/额度）。 */
  const loadQuota = useCallback(async (targetUserId: number | null) => {
    if (targetUserId == null) {
      setQuota(null);
      quotaUserRef.current = null;
      return;
    }
    const reqId = (quotaReqRef.current += 1);
    try {
      const data = await getUserQuota(targetUserId);
      if (reqId !== quotaReqRef.current) {
        // 过期响应：这期间用户已经切到别的用户，写进去只会是错的值
        return;
      }
      setQuota(data);
      /*
       * 只有**换用户**时才把输入框重置成服务端的值。
       *
       * 为什么：`load()` 里会顺带调一次 loadQuota，而 load() 会因为筛选条件等变化重跑。
       * 若每次响应都重置输入框，用户「刚选完用户、正在输入额度」时晚到的那次重拉就会把输入清空 ——
       * 点保存时提交的其实是空值（=不限），表现为「设了极小额度却没有超限提示」（实测全量跑必现，
       * 单独跑只是后端更快、恰好没撞上）。
       */
      if (quotaUserRef.current !== targetUserId) {
        quotaUserRef.current = targetUserId;
        setQuotaDaily(data.dailyQuota == null ? "" : String(data.dailyQuota));
        setQuotaMonthly(data.monthlyQuota == null ? "" : String(data.monthlyQuota));
        setQuotaMode(data.mode);
      }
    } catch {
      setQuota(null);
    }
  }, []);


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
      void loadQuota(selectedUser);
      setRecords(page.records);
      setTotal(page.total);
    } catch (err) {
      setError(err instanceof Error ? err.message : "加载审计数据失败");
    } finally {
      setLoading(false);
    }
  }, [selectedUser, from, to, loadQuota]);


  /** 保存额度（空 = 不限）。 */
  async function saveQuota() {
    const targetUserId = Number(selectedUser);
    if (!targetUserId) {
      setQuotaNotice("请先选择要设置额度的用户");
      return;
    }
    try {
      const saved = await setUserQuota(targetUserId, {
        dailyQuota: quotaDaily.trim() === "" ? null : Number(quotaDaily),
        monthlyQuota: quotaMonthly.trim() === "" ? null : Number(quotaMonthly),
        currency: quota?.currency ?? "CNY",
        mode: quotaMode,
      });
      setQuota(saved);
      setQuotaNotice("额度已保存");
    } catch (e) {
      setQuotaNotice(`保存失败：${e instanceof Error ? e.message : String(e)}`);
    }
  }

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

      <div className="grid gap-3 sm:grid-cols-3 lg:grid-cols-6">
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
            {fmt(summary?.totalTokens ?? 0)}
          </CardContent>
        </Card>
        <Card data-testid="kpi-cost">
          <CardHeader className="p-3">
            <CardTitle className="text-xs font-normal">成本</CardTitle>
          </CardHeader>
          <CardContent className="p-3 pt-0 text-2xl font-semibold">
            {money(summary?.costTotal)}
            <span className="text-muted-foreground ml-1 text-xs font-normal">
              {summary?.costCurrency && summary.costCurrency !== "MIXED" ? summary.costCurrency : "（多币种）"}
            </span>
          </CardContent>
        </Card>
        <Card data-testid="kpi-records">
          <CardHeader className="p-3">
            <CardTitle className="text-xs font-normal">调用次数</CardTitle>
          </CardHeader>
          <CardContent className="p-3 pt-0 text-2xl font-semibold">
            {fmt(summary?.records ?? 0)}
          </CardContent>
        </Card>
        <Card data-testid="kpi-cache">
          <CardHeader className="p-3">
            <CardTitle className="text-xs font-normal">缓存命中 Tokens</CardTitle>
          </CardHeader>
          <CardContent className="p-3 pt-0 text-2xl font-semibold">
            {fmt(summary?.cacheTokens ?? 0)}
            <span className="text-muted-foreground ml-1 text-xs font-normal">
              {(() => {
                const total = Number(summary?.promptTokens ?? 0);
                const hit = Number(summary?.cacheTokens ?? 0);
                return total > 0 ? `命中率 ${((hit / total) * 100).toFixed(1)}%` : "无命中";
              })()}
            </span>
          </CardContent>
        </Card>
        <Card data-testid="kpi-scope">
          <CardHeader className="p-3">
            <CardTitle className="text-xs font-normal">会话 / Agent / 模型</CardTitle>
          </CardHeader>
          <CardContent className="p-3 pt-0 text-2xl font-semibold">
            {fmt(summary?.sessions ?? 0)}
            <span className="text-muted-foreground ml-1 text-sm font-normal">
              / {fmt(summary?.agentCount ?? 0)} / {fmt(summary?.modelCount ?? 0)}
            </span>
          </CardContent>
        </Card>
      </div>

      <Card data-testid="quota-card">
        <CardHeader className="p-3">
          <CardTitle className="text-sm">支出额度（用户 #{selectedUser || "-"}）</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3 p-3 pt-0">
          <div className="grid gap-3 text-sm sm:grid-cols-2" data-testid="quota-usage">
            <div>
              今日已用 <span className="font-semibold">{quota?.usedToday ?? 0}</span> {quota?.currency ?? "CNY"}
              <span className="text-muted-foreground ml-2">
                / 日额度 {quota?.dailyQuota ?? "不限"}
              </span>
            </div>
            <div>
              本月已用 <span className="font-semibold">{quota?.usedMonth ?? 0}</span> {quota?.currency ?? "CNY"}
              <span className="text-muted-foreground ml-2">
                / 月额度 {quota?.monthlyQuota ?? "不限"}
              </span>
            </div>
          </div>
          {quota?.exceeded && (
            <div className="text-destructive text-xs" data-testid="quota-exceeded">
              已超出额度：{quota.message}
            </div>
          )}
          {quota?.nearLimit && (
            <div className="text-xs text-amber-600" data-testid="quota-near">
              已用额度接近上限（≥80%）
            </div>
          )}
          <div className="flex flex-wrap items-end gap-2">
            <label className="space-y-1 text-xs">
              日额度
              <Input
                className="h-8 w-28"
                data-testid="quota-daily"
                placeholder="不限"
                value={quotaDaily}
                onChange={(e) => setQuotaDaily(e.target.value)}
              />
            </label>
            <label className="space-y-1 text-xs">
              月额度
              <Input
                className="h-8 w-28"
                data-testid="quota-monthly"
                placeholder="不限"
                value={quotaMonthly}
                onChange={(e) => setQuotaMonthly(e.target.value)}
              />
            </label>
            <label className="space-y-1 text-xs">
              超限行为
              <select
                className="border-input h-8 rounded-md border px-2 text-xs"
                data-testid="quota-mode"
                value={quotaMode}
                onChange={(e) => setQuotaMode(e.target.value as "BLOCK" | "WARN")}
              >
                <option value="BLOCK">BLOCK（拒绝发起）</option>
                <option value="WARN">WARN（仅告警）</option>
              </select>
            </label>
            <Button size="sm" data-testid="quota-save" onClick={() => void saveQuota()}>
              保存额度
            </Button>
            {quotaNotice && (
              <span className="text-muted-foreground text-xs" data-testid="quota-notice">
                {quotaNotice}
              </span>
            )}
          </div>
        </CardContent>
      </Card>

      <Card>
        <CardHeader className="p-3">
          <CardTitle className="text-sm">按日趋势</CardTitle>
        </CardHeader>
        <CardContent className="p-3 pt-0">
          <DailyTrendChart rows={summary?.byDay ?? []} />
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
                <TableHead>模型</TableHead>
                <TableHead>占比</TableHead>
                <TableHead>输入</TableHead>
                <TableHead>缓存命中</TableHead>
                <TableHead>输出</TableHead>
                <TableHead>合计</TableHead>
                <TableHead>单价（入/出/缓存）</TableHead>
                <TableHead>成本</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {(summary?.byModel ?? []).slice(0, 10).map((row, index) => (
                <TableRow key={`${row.model_id}-${index}`}>
                  <TableCell className="max-w-[16rem] truncate" title={row.model_name ?? row.model_key ?? ""}>
                    {row.model_name ?? row.model_key ?? `#${row.model_id ?? "-"}`}
                  </TableCell>
                  <TableCell className="text-xs tabular-nums">
                    {(() => {
                      const all = (summary?.byModel ?? []).reduce(
                        (sum, r) => sum + (Number(r.total_tokens) || 0),
                        0,
                      );
                      const ratio = all > 0 ? ((Number(row.total_tokens) || 0) / all) * 100 : 0;
                      return `${ratio.toFixed(1)}%`;
                    })()}
                  </TableCell>
                  <TableCell className="tabular-nums">{fmt(row.prompt_tokens)}</TableCell>
                  <TableCell className="tabular-nums">{fmt(row.cache_tokens)}</TableCell>
                  <TableCell className="tabular-nums">{fmt(row.completion_tokens)}</TableCell>
                  <TableCell className="tabular-nums">{fmt(row.total_tokens)}</TableCell>
                  <TableCell className="font-mono text-xs">
                    {row.price_input != null || row.price_output != null || row.price_cache != null
                      ? `${row.price_input ?? "-"} / ${row.price_output ?? "-"} / ${row.price_cache ?? "-"}`
                      : "-"}
                  </TableCell>
                  <TableCell className="font-mono text-xs">
                    {row.cost_total != null
                      ? `${Number(row.cost_total).toFixed(6)} ${row.currency ?? ""}`
                      : "-"}
                  </TableCell>
                </TableRow>
              ))}
              {(summary?.byModel ?? []).length === 0 && (
                <TableRow>
                  <TableCell colSpan={6} className="text-muted-foreground text-center text-sm">
                    暂无数据
                  </TableCell>
                </TableRow>
              )}
            </TableBody>
          </Table>
          {(summary?.byModel?.length ?? 0) > 10 && (
            <p className="text-muted-foreground mt-2 text-[11px]">
              仅列出用量前 10 的模型（共 {summary?.byModel?.length} 个）
            </p>
          )}
        </CardContent>
      </Card>

      <div className="grid gap-3 lg:grid-cols-2">
        <Card data-testid="by-agent">
          <CardHeader className="p-3">
            <CardTitle className="text-sm">按 Agent 分布</CardTitle>
          </CardHeader>
          <CardContent className="p-3 pt-0">
            <BreakdownBars
              rows={summary?.byAgent ?? []}
              labelOf={(row) => row.agent_name ?? `Agent #${row.agent_id ?? "-"}`}
              testId="agent-bars"
            />
          </CardContent>
        </Card>
        <Card data-testid="by-provider">
          <CardHeader className="p-3">
            <CardTitle className="text-sm">按供应商占比</CardTitle>
          </CardHeader>
          <CardContent className="p-3 pt-0">
            <Donut
              rows={summary?.byProvider ?? []}
              labelOf={(row) => String(row.provider_type ?? "未知")}
              ariaLabel="按供应商 Token 占比"
              testId="provider-donut"
            />
          </CardContent>
        </Card>
      </div>

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
                <TableHead>用户</TableHead>
                <TableHead>Agent</TableHead>
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
                  <TableCell className="text-xs">{row.userName ?? `#${row.userId}`}</TableCell>
                  <TableCell className="text-xs">{row.agentName ?? "-"}</TableCell>
                  <TableCell
                    className="max-w-[14rem] truncate text-xs"
                    title={`${row.modelName ?? ""}${row.modelId ? ` (#${row.modelId})` : ""}`}
                  >
                    {row.modelName ?? (row.modelId ? `#${row.modelId}` : "-")}
                  </TableCell>
                  <TableCell className="tabular-nums">{fmt(row.promptTokens)}</TableCell>
                  <TableCell className="tabular-nums">{fmt(row.completionTokens)}</TableCell>
                  <TableCell className="tabular-nums font-medium">{fmt(row.totalTokens)}</TableCell>
                </TableRow>
              ))}
              {records.length === 0 && (
                <TableRow>
                  <TableCell colSpan={9} className="text-muted-foreground text-center text-sm">
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
