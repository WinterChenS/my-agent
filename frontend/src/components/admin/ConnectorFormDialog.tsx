import { useEffect, useMemo, useState } from "react";
import { toast } from "sonner";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import {
  createConnector,
  testConnector,
  testDraftConnector,
  updateConnector,
  type ConnectorInfo,
  type ConnectorPayload,
  type ConnectorTypeInfo,
  type InboundMode,
} from "@/api/connectors";

/**
 * 连接器配置表单。
 *
 * <p>字段**全部来自后端 `/api/connectors/types`**：加一种连接器（日历、云盘…）只改后端描述，
 * 这个表单不用动。密钥字段用 password 输入，编辑时留空表示不改。
 */
export interface ConnectorFormValue {
  id: number | null;
  type: string;
  name: string;
  alias: string;
  enabled: boolean;
  inboundMode: InboundMode;
  config: Record<string, string>;
}

export function emptyConnectorForm(type: string): ConnectorFormValue {
  return { id: null, type, name: "", alias: "", enabled: true, inboundMode: "OFF", config: {} };
}

export function ConnectorFormDialog({
  open,
  types,
  initial,
  onClose,
  onSaved,
}: {
  open: boolean;
  types: ConnectorTypeInfo[];
  initial: ConnectorFormValue | null;
  onClose: () => void;
  onSaved: () => void;
}) {
  const [form, setForm] = useState<ConnectorFormValue>(
    initial ?? emptyConnectorForm(types[0]?.type ?? "MAIL"),
  );
  const [saving, setSaving] = useState(false);
  const [testing, setTesting] = useState(false);
  const [testResult, setTestResult] = useState<{ ok: boolean; message: string; steps: string[] } | null>(
    null,
  );

  useEffect(() => {
    if (open) {
      setForm(initial ?? emptyConnectorForm(types[0]?.type ?? "MAIL"));
      setTestResult(null);
    }
  }, [open, initial, types]);

  const descriptor = useMemo(
    () => types.find((item) => item.type === form.type) ?? types[0],
    [types, form.type],
  );

  if (!descriptor) {
    return null;
  }

  const payload = (): ConnectorPayload => ({
    type: form.type,
    name: form.name.trim(),
    alias: form.alias.trim() || null,
    enabled: form.enabled,
    config: form.config,
    inboundMode: form.inboundMode,
  });

  const setConfig = (key: string, value: string) =>
    setForm((prev) => ({ ...prev, config: { ...prev.config, [key]: value } }));

  const runTest = async () => {
    setTesting(true);
    setTestResult(null);
    try {
      const result =
        form.id == null
          ? await testDraftConnector(payload())
          : await testConnector(form.id);
      setTestResult(result);
      if (result.ok) {
        toast.success("连接成功");
      } else {
        toast.error(result.message);
      }
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "测试连接失败");
    } finally {
      setTesting(false);
    }
  };

  const save = async () => {
    if (!form.name.trim()) {
      toast.error("请填写名称（用来区分多个邮箱）");
      return;
    }
    setSaving(true);
    try {
      if (form.id == null) {
        await createConnector(payload());
      } else {
        await updateConnector(form.id, payload());
      }
      toast.success("已保存");
      onSaved();
      onClose();
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "保存失败");
    } finally {
      setSaving(false);
    }
  };

  return (
    <Dialog open={open} onOpenChange={(value) => !value && onClose()}>
      <DialogContent className="max-h-[85vh] overflow-y-auto sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>
            {form.id == null ? `添加${descriptor.displayName}连接器` : "编辑连接器"}
          </DialogTitle>
          <DialogDescription>{descriptor.description}</DialogDescription>
        </DialogHeader>

        <div className="space-y-3">
          <div className="grid gap-3 sm:grid-cols-2">
            <label className="space-y-1 text-sm">
              <span className="font-medium">
                名称 <span className="text-destructive">*</span>
              </span>
              <Input
                value={form.name}
                placeholder="工作邮箱"
                onChange={(event) => setForm({ ...form, name: event.target.value })}
              />
            </label>
            <label className="space-y-1 text-sm">
              <span className="font-medium">别名</span>
              <Input
                value={form.alias}
                placeholder="work（可选，多个邮箱时用于区分）"
                onChange={(event) => setForm({ ...form, alias: event.target.value })}
              />
            </label>
          </div>

          {descriptor.fields.map((field) => (
            <label key={field.key} className="block space-y-1 text-sm">
              <span className="font-medium">
                {field.label}
                {field.required ? <span className="text-destructive"> *</span> : null}
              </span>
              <Input
                type={field.secret ? "password" : "text"}
                value={form.config[field.key] ?? ""}
                placeholder={field.secret ? "保持不变（留空表示不修改）" : field.placeholder}
                onChange={(event) => setConfig(field.key, event.target.value)}
              />
              {field.hint ? (
                <span className="text-muted-foreground block text-xs">{field.hint}</span>
              ) : null}
            </label>
          ))}

          <div className="flex items-center gap-2 text-sm">
            <input
              id="connector-enabled"
              type="checkbox"
              checked={form.enabled}
              onChange={(event) => setForm({ ...form, enabled: event.target.checked })}
            />
            <label htmlFor="connector-enabled">启用（停用后 Agent 拿不到这个连接器）</label>
          </div>

          {descriptor.inboundSupported ? (
            <label className="block space-y-1 text-sm">
              <span className="font-medium">新邮件处理</span>
              <select
                className="border-input h-9 w-full rounded-md border bg-transparent px-2 text-sm"
                value={form.inboundMode}
                onChange={(event) =>
                  setForm({ ...form, inboundMode: event.target.value as InboundMode })
                }
              >
                <option value="OFF">只记录（推荐先这样）</option>
                <option value="NOTIFY">收到就推送 IM 通知</option>
                <option value="AUTOREPLY">交给 Agent 自动回复（有防轰炸护栏）</option>
              </select>
              <span className="text-muted-foreground block text-xs">
                自动回复只对「绑定给某个 Agent」的连接器生效，且同一线程只回一次。
              </span>
            </label>
          ) : null}

          {testResult ? (
            <div
              className={
                testResult.ok
                  ? "rounded-md border border-emerald-300 bg-emerald-50 p-3 text-sm text-emerald-900"
                  : "rounded-md border border-red-300 bg-red-50 p-3 text-sm text-red-900"
              }
            >
              <div className="font-medium">{testResult.message}</div>
              {testResult.steps.length > 0 ? (
                <ul className="mt-1 list-disc pl-5 text-xs">
                  {testResult.steps.map((step) => (
                    <li key={step}>{step}</li>
                  ))}
                </ul>
              ) : null}
            </div>
          ) : null}
        </div>

        <DialogFooter className="gap-2">
          <Button variant="outline" onClick={runTest} disabled={testing || saving}>
            {testing ? "测试中…" : "测试连接"}
          </Button>
          <Button variant="ghost" onClick={onClose} disabled={saving}>
            取消
          </Button>
          <Button onClick={save} disabled={saving}>
            {saving ? "保存中…" : "保存"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

/** 列表里的状态徽标：UNKNOWN 表示"还没测过"，别显示成失败吓人。 */
export function ConnectorStatusBadge({ connector }: { connector: ConnectorInfo }) {
  if (!connector.enabled) {
    return <Badge variant="secondary">已停用</Badge>;
  }
  if (connector.status === "OK") {
    return <Badge className="bg-emerald-600">正常</Badge>;
  }
  if (connector.status === "ERROR") {
    return <Badge variant="destructive">连接异常</Badge>;
  }
  return <Badge variant="outline">未测试</Badge>;
}
