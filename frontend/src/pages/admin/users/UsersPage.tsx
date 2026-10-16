import { useCallback, useEffect, useState } from "react";
import { toast } from "sonner";
import { KeyRound, Pencil, Plus, RefreshCw, Trash2 } from "lucide-react";
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
  createUser,
  deleteUser,
  listUsers,
  resetUserPassword,
  updateUser,
  type UserRow,
} from "@/api/users";
import { useAuthStore } from "@/stores/auth";

interface FormState {
  id: number | null;
  username: string;
  password: string;
  displayName: string;
  role: "ADMIN" | "USER";
  status: "ENABLED" | "DISABLED";
}

const EMPTY: FormState = {
  id: null,
  username: "",
  password: "",
  displayName: "",
  role: "USER",
  status: "ENABLED",
};

/**
 * 用户管理（C1，仅 ADMIN）。
 *
 * 后端还有两道「别把管理员锁在门外」的保护（不能禁用/删除自己、不能干掉最后一个可用 ADMIN），
 * 这里把同样的规则在界面上也提示出来 —— 否则用户点了才被拒，体验差且不易理解。
 */
export default function UsersPage() {
  const me = useAuthStore((s) => s.user);
  const [rows, setRows] = useState<UserRow[]>([]);
  const [loading, setLoading] = useState(false);
  const [dialogOpen, setDialogOpen] = useState(false);
  const [form, setForm] = useState<FormState>(EMPTY);
  const [pwdTarget, setPwdTarget] = useState<UserRow | null>(null);
  const [newPassword, setNewPassword] = useState("");

  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(10);
  const [total, setTotal] = useState(0);
  const [keyword, setKeyword] = useState("");
  const [roleFilter, setRoleFilter] = useState<"" | "ADMIN" | "USER">("");
  const [statusFilter, setStatusFilter] = useState<"" | "ENABLED" | "DISABLED">("");
  const [applied, setApplied] = useState({
    keyword: "",
    role: "" as "" | "ADMIN" | "USER",
    status: "" as "" | "ENABLED" | "DISABLED",
  });

  const refresh = useCallback(async () => {
    setLoading(true);
    try {
      const result = await listUsers({
        current: page,
        size: pageSize,
        keyword: applied.keyword,
        role: applied.role,
        status: applied.status,
      });
      setRows(result.records);
      setTotal(result.total);
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "加载用户列表失败");
    } finally {
      setLoading(false);
    }
  }, [page, pageSize, applied]);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  function openCreate() {
    setForm(EMPTY);
    setDialogOpen(true);
  }

  function openEdit(row: UserRow) {
    setForm({
      id: row.id,
      username: row.username,
      password: "",
      displayName: row.displayName ?? "",
      role: row.role,
      status: row.status,
    });
    setDialogOpen(true);
  }

  async function save() {
    if (form.id == null && !form.username.trim()) {
      toast.error("用户名不能为空");
      return;
    }
    if (form.id == null && !form.password) {
      toast.error("密码不能为空");
      return;
    }
    try {
      if (form.id == null) {
        await createUser({
          username: form.username.trim(),
          password: form.password,
          displayName: form.displayName.trim() || null,
          role: form.role,
          status: form.status,
        });
        toast.success("已创建用户");
        // 列表按 id 升序 + 分页：新建的用户在**最后一页**，跳过去（否则用户会以为没建成功，
        // 实测全量跑时用户一多就直接找不到新行）
        setPage(Math.max(1, Math.ceil((total + 1) / pageSize)));
      } else {
        await updateUser(form.id, {
          displayName: form.displayName.trim() || null,
          role: form.role,
          status: form.status,
          // 编辑弹窗里密码留空 = 不改密码
          password: form.password ? form.password : undefined,
        });
        toast.success("已保存");
      }
      setDialogOpen(false);
      await refresh();
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "保存失败");
    }
  }

  async function savePassword() {
    if (!pwdTarget) {
      return;
    }
    try {
      await resetUserPassword(pwdTarget.id, newPassword);
      toast.success(`已重置 ${pwdTarget.username} 的密码`);
      setPwdTarget(null);
      setNewPassword("");
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "重置密码失败");
    }
  }

  async function remove(row: UserRow) {
    if (!confirm(`删除用户「${row.username}」？`)) {
      return;
    }
    try {
      await deleteUser(row.id);
      toast.success("已删除");
      await refresh();
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "删除失败");
    }
  }

  const isSelf = (row: UserRow) => me?.id === row.id;

  return (
    <div className="h-full space-y-4 overflow-auto p-6">
      <div className="flex items-center gap-3">
        <div>
          <h1 className="text-xl font-semibold">用户管理</h1>
          <p className="text-muted-foreground text-xs">
            创建 / 停用账号、调整角色、重置密码。当前登录账号与「最后一个可用管理员」受保护，不能被禁用或删除。
          </p>
        </div>
        <Button className="ml-auto" data-testid="user-new" onClick={openCreate}>
          <Plus className="size-4" /> 新建用户
        </Button>
        <Button variant="outline" onClick={() => void refresh()} disabled={loading}>
          <RefreshCw className="size-4" /> 刷新
        </Button>
      </div>

      <Card>
        <CardHeader className="p-3">
          <CardTitle className="text-sm">用户列表（{total}）</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3 p-3 pt-0">
          {/* 筛选 */}
          <div className="flex flex-wrap items-end gap-2" data-testid="user-filters">
            <label className="space-y-1 text-xs">
              名称包含
              <Input
                className="h-8 w-44"
                data-testid="user-filter-keyword"
                placeholder="用户名或显示名"
                value={keyword}
                onChange={(e) => setKeyword(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === "Enter") {
                    setPage(1);
                    setApplied({ keyword, role: roleFilter, status: statusFilter });
                  }
                }}
              />
            </label>
            <label className="space-y-1 text-xs">
              角色
              <select
                className="border-input h-8 rounded-md border bg-transparent px-2 text-xs"
                data-testid="user-filter-role"
                value={roleFilter}
                onChange={(e) => setRoleFilter(e.target.value as "" | "ADMIN" | "USER")}
              >
                <option value="">全部</option>
                <option value="ADMIN">ADMIN</option>
                <option value="USER">USER</option>
              </select>
            </label>
            <label className="space-y-1 text-xs">
              状态
              <select
                className="border-input h-8 rounded-md border bg-transparent px-2 text-xs"
                data-testid="user-filter-status"
                value={statusFilter}
                onChange={(e) => setStatusFilter(e.target.value as "" | "ENABLED" | "DISABLED")}
              >
                <option value="">全部</option>
                <option value="ENABLED">ENABLED</option>
                <option value="DISABLED">DISABLED</option>
              </select>
            </label>
            <Button
              size="sm"
              data-testid="user-filter-apply"
              onClick={() => {
                setPage(1);
                setApplied({ keyword, role: roleFilter, status: statusFilter });
              }}
            >
              查询
            </Button>
            <Button
              size="sm"
              variant="outline"
              data-testid="user-filter-reset"
              onClick={() => {
                setKeyword("");
                setRoleFilter("");
                setStatusFilter("");
                setPage(1);
                setApplied({ keyword: "", role: "", status: "" });
              }}
            >
              重置
            </Button>
          </div>

          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>用户名</TableHead>
                <TableHead>显示名</TableHead>
                <TableHead>角色</TableHead>
                <TableHead>状态</TableHead>
                <TableHead className="text-right">操作</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {rows.map((row) => (
                <TableRow key={row.id} data-testid="user-row">
                  <TableCell className="font-medium">
                    {row.username}
                    {isSelf(row) && (
                      <span className="text-muted-foreground ml-1 text-xs">（我）</span>
                    )}
                  </TableCell>
                  <TableCell className="text-xs">{row.displayName ?? "-"}</TableCell>
                  <TableCell>
                    <Badge variant={row.role === "ADMIN" ? "default" : "outline"}>{row.role}</Badge>
                  </TableCell>
                  <TableCell>
                    <Badge variant={row.status === "ENABLED" ? "secondary" : "outline"}>
                      {row.status}
                    </Badge>
                  </TableCell>
                  <TableCell className="text-right">
                    <div className="flex justify-end gap-1">
                      <Button
                        size="icon"
                        variant="ghost"
                        title="编辑"
                        data-testid="user-edit"
                        onClick={() => openEdit(row)}
                      >
                        <Pencil className="size-3.5" />
                      </Button>
                      <Button
                        size="icon"
                        variant="ghost"
                        title="重置密码"
                        data-testid="user-reset"
                        onClick={() => {
                          setPwdTarget(row);
                          setNewPassword("");
                        }}
                      >
                        <KeyRound className="size-3.5" />
                      </Button>
                      <Button
                        size="icon"
                        variant="ghost"
                        title="删除"
                        data-testid="user-delete"
                        onClick={() => void remove(row)}
                      >
                        <Trash2 className="size-3.5" />
                      </Button>
                    </div>
                  </TableCell>
                </TableRow>
              ))}
              {rows.length === 0 && (
                <TableRow>
                  <TableCell colSpan={5} className="text-muted-foreground text-center text-sm">
                    没有匹配的用户。
                  </TableCell>
                </TableRow>
              )}
            </TableBody>
          </Table>

          {/* 分页 */}
          <div className="flex flex-wrap items-center justify-between gap-2 text-sm" data-testid="user-pagination">
            <div className="text-muted-foreground text-xs">
              共 {total} 条，第 {page} / {Math.max(1, Math.ceil(total / pageSize))} 页
            </div>
            <div className="flex items-center gap-2">
              <label className="text-muted-foreground text-xs">
                每页
                <select
                  className="border-input ml-1 h-7 rounded-md border px-1 text-xs"
                  data-testid="user-page-size"
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
                data-testid="user-prev"
                disabled={page <= 1 || loading}
                onClick={() => setPage((p) => Math.max(1, p - 1))}
              >
                上一页
              </Button>
              <Button
                variant="outline"
                size="sm"
                data-testid="user-next"
                disabled={page >= Math.ceil(total / pageSize) || loading}
                onClick={() => setPage((p) => p + 1)}
              >
                下一页
              </Button>
            </div>
          </div>
        </CardContent>
      </Card>

      {/* 新建 / 编辑 */}
      <Dialog open={dialogOpen} onOpenChange={setDialogOpen}>
        <DialogContent className="max-w-lg">
          <DialogHeader>
            <DialogTitle>{form.id == null ? "新建用户" : `编辑用户：${form.username}`}</DialogTitle>
          </DialogHeader>
          <div className="grid gap-3">
            {form.id == null && (
              <label className="space-y-1 text-sm">
                用户名 <span className="text-destructive">*</span>
                <Input
                  data-testid="user-username"
                  value={form.username}
                  onChange={(e) => setForm({ ...form, username: e.target.value })}
                  placeholder="2-64 位字母/数字/下划线/中划线"
                />
              </label>
            )}
            <label className="space-y-1 text-sm">
              {form.id == null ? "密码" : "重置密码（留空=不改）"}{" "}
              {form.id == null && <span className="text-destructive">*</span>}
              <Input
                data-testid="user-password"
                type="password"
                value={form.password}
                onChange={(e) => setForm({ ...form, password: e.target.value })}
                placeholder="至少 6 位"
              />
            </label>
            <label className="space-y-1 text-sm">
              显示名
              <Input
                data-testid="user-display-name"
                value={form.displayName}
                onChange={(e) => setForm({ ...form, displayName: e.target.value })}
                placeholder="留空则用用户名"
              />
            </label>
            <div className="grid grid-cols-2 gap-3">
              <label className="space-y-1 text-sm">
                角色
                <select
                  className="border-input h-9 w-full rounded-md border bg-transparent px-2 text-sm"
                  data-testid="user-role"
                  value={form.role}
                  onChange={(e) => setForm({ ...form, role: e.target.value as "ADMIN" | "USER" })}
                >
                  <option value="USER">USER（普通用户）</option>
                  <option value="ADMIN">ADMIN（管理员）</option>
                </select>
              </label>
              <label className="space-y-1 text-sm">
                状态
                <select
                  className="border-input h-9 w-full rounded-md border bg-transparent px-2 text-sm"
                  data-testid="user-status"
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
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setDialogOpen(false)}>
              取消
            </Button>
            <Button data-testid="user-save" onClick={() => void save()}>
              保存
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* 重置密码 */}
      <Dialog open={pwdTarget != null} onOpenChange={(open) => !open && setPwdTarget(null)}>
        <DialogContent className="max-w-md">
          <DialogHeader>
            <DialogTitle>重置密码：{pwdTarget?.username}</DialogTitle>
          </DialogHeader>
          <label className="space-y-1 text-sm">
            新密码 <span className="text-destructive">*</span>
            <Input
              data-testid="reset-password"
              type="password"
              value={newPassword}
              onChange={(e) => setNewPassword(e.target.value)}
              placeholder="至少 6 位"
            />
          </label>
          <DialogFooter>
            <Button variant="outline" onClick={() => setPwdTarget(null)}>
              取消
            </Button>
            <Button data-testid="reset-password-save" onClick={() => void savePassword()}>
              确认重置
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
