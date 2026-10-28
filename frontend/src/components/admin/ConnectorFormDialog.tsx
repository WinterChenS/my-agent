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
  type ConnectorTypeField,
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
            <ConnectorField
              key={field.key}
              field={field}
              value={
                // inboundMode 落独立的列（不是 config JSON），其余字段都在 config 里
                field.key === "inboundMode" ? form.inboundMode : (form.config[field.key] ?? "")
              }
              onChange={(value) =>
                field.key === "inboundMode"
                  ? setForm((prev) => ({ ...prev, inboundMode: value as InboundMode }))
                  : setConfig(field.key, value)
              }
            />
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

/**
 * 单个配置项：**按后端声明的控件类型**渲染。
 *
 * 以前所有字段都是文本框，于是「IMAP 使用 SSL」「允许发信」这种枚举要用户手打 true/false，
 * 「新邮件处理」还得记 OFF/NOTIFY/AUTOREPLY —— 既容易填错也没法校验。
 * 现在枚举一律下拉、端口用数字输入，加新的连接器类型不用再改这里。
 */
function ConnectorField({
  field,
  value,
  onChange,
}: {
  field: ConnectorTypeField;
  value: string;
  onChange: (value: string) => void;
}) {
  const label = (
    <span className="font-medium">
      {field.label}
      {field.required ? <span className="text-destructive"> *</span> : null}
    </span>
  );
  const hint = field.hint ? (
    <span className="text-muted-foreground block text-xs">{field.hint}</span>
  ) : null;

  if (field.type === "SELECT") {
    return (
      <label className="block space-y-1 text-sm">
        {label}
        <select
          className="border-input h-9 w-full rounded-md border bg-transparent px-2 text-sm"
          value={value || field.options[0]?.value || ""}
          onChange={(event) => onChange(event.target.value)}
          data-testid={`connector-field-${field.key}`}
        >
          {field.options.map((option) => (
            <option key={option.value} value={option.value}>
              {option.label}
            </option>
          ))}
        </select>
        {hint}
      </label>
    );
  }

  return (
    <label className="block space-y-1 text-sm">
      {label}
      <Input
        type={field.type === "PASSWORD" ? "password" : field.type === "NUMBER" ? "number" : "text"}
        inputMode={field.type === "NUMBER" ? "numeric" : undefined}
        value={value}
        placeholder={
          field.type === "PASSWORD" ? "保持不变（留空表示不修改）" : field.placeholder
        }
        onChange={(event) => onChange(event.target.value)}
        data-testid={`connector-field-${field.key}`}
      />
      {hint}
    </label>
  );
}
