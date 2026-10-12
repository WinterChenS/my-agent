import { useCallback, useEffect, useState } from "react";
import { toast } from "sonner";
import { Plus, PlugZap, RefreshCw, Trash2, Pencil } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import {
  PROVIDER_TYPES,
  createModel,
  createProvider,
  deleteModel,
  deleteProvider,
  listModels,
  listProviders,
  testProvider,
  updateModel,
  updateProvider,
  type ModelInfo,
  type Provider,
  type ProviderType,
} from "@/api/models";
import { maxTokensWarning } from "@/lib/utils";

interface ProviderForm {
  id: number | null;
  name: string;
  providerType: ProviderType;
  baseUrl: string;
  apiKey: string;
  status: "ENABLED" | "DISABLED";
}

interface ModelForm {
  id: number | null;
  providerId: number | null;
  modelId: string;
  displayName: string;
  temperature: string;
  topP: string;
  contextWindow: string;
  maxTokens: string;
  priceInput: string;
  priceOutput: string;
  priceCurrency: string;
  status: "ENABLED" | "DISABLED";
}

const EMPTY_PROVIDER: ProviderForm = {
  id: null,
  name: "",
  providerType: "OPENAI_COMPATIBLE",
  baseUrl: "",
  apiKey: "",
  status: "ENABLED",
};

const EMPTY_MODEL: ModelForm = {
  id: null,
  providerId: null,
  modelId: "",
  displayName: "",
  temperature: "",
  topP: "",
  contextWindow: "",
  maxTokens: "",
  priceInput: "",
  priceOutput: "",
  priceCurrency: "CNY",
  status: "ENABLED",
};

export default function ModelsPage() {
  const [providers, setProviders] = useState<Provider[]>([]);
  const [models, setModels] = useState<ModelInfo[]>([]);
  const [loading, setLoading] = useState(false);

  const [providerDialog, setProviderDialog] = useState(false);
  const [providerForm, setProviderForm] = useState<ProviderForm>(EMPTY_PROVIDER);
  const [modelDialog, setModelDialog] = useState(false);
  const [modelForm, setModelForm] = useState<ModelForm>(EMPTY_MODEL);
  const [testingId, setTestingId] = useState<number | null>(null);

  /** 把探针失败原因转成可操作的信息（400/404/401/超时分开给提示）。 */
  function describeFailure(result: { error: string | null }, modelLabel: string) {
    const raw = result.error ?? "未知原因";
    const lower = raw.toLowerCase();
    const hints: string[] = [];
    if (lower.includes("no model") || lower.includes("not_found") || lower.includes("404")) {
      hints.push(
        `模型 ID「${modelLabel}」可能不被该端点支持；错误信息里通常列出了可用模型，请对照修改。`,
      );
    }
    if (lower.includes("401") || lower.includes("invalid_api_key") || lower.includes("unauthorized")) {
      hints.push("API Key 可能无效，或没有访问该模型的权限。");
    }
    if (
      lower.includes("timeout") ||
      lower.includes("closedchannel") ||
      lower.includes("refused") ||
      lower.includes("connect")
    ) {
      hints.push("端点不可达：检查 baseUrl 是否正确、网络是否通。");
    }
    return { raw, hint: hints.join(" ") };
  }

  const refresh = useCallback(async () => {
    setLoading(true);
    try {
      const [p, m] = await Promise.all([listProviders(), listModels()]);
      setProviders(p.records);
      setModels(m.records);
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "加载失败");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  async function saveProvider() {
    if (!providerForm.name.trim()) {
      toast.error("名称不能为空");
      return;
    }
    const payload = {
      name: providerForm.name.trim(),
      providerType: providerForm.providerType,
      baseUrl: providerForm.baseUrl.trim() || null,
      // 留空表示「不修改密钥」，后端会保留旧值
      apiKey: providerForm.apiKey.trim() || null,
      status: providerForm.status,
    };
    try {
      if (providerForm.id == null) {
        await createProvider(payload);
        toast.success("已创建供应商");
      } else {
        await updateProvider(providerForm.id, payload);
        toast.success("已保存（api_key 留空则保留原值）");
      }
      setProviderDialog(false);
      await refresh();
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "保存失败");
    }
  }

  async function saveModel() {
    if (!modelForm.providerId) {
      toast.error("请选择供应商");
      return;
    }
    if (!modelForm.modelId.trim()) {
      toast.error("模型 ID 不能为空");
      return;
    }
    const num = (text: string): number | null => (text.trim() ? Number(text) : null);
    // Phase 15：这几项都作为显式字段提交（后端会校验范围，并确保"输出长度 ≤ 上下文长度"）
    const payload = {
      providerId: modelForm.providerId,
      modelId: modelForm.modelId.trim(),
      displayName: modelForm.displayName.trim() || null,
      params: {} as Record<string, unknown>,
      temperature: num(modelForm.temperature),
      topP: num(modelForm.topP),
      contextWindow: num(modelForm.contextWindow),
      maxTokens: num(modelForm.maxTokens),
      priceInput: num(modelForm.priceInput),
      priceOutput: num(modelForm.priceOutput),
      priceCurrency: modelForm.priceCurrency || "CNY",
      status: modelForm.status,
    };
    if (
      payload.contextWindow != null &&
      payload.maxTokens != null &&
      payload.maxTokens > payload.contextWindow
    ) {
      toast.error("输出长度不能大于上下文长度");
      return;
    }
    try {
      if (modelForm.id == null) {
        await createModel(payload);
        toast.success("已创建模型");
      } else {
        await updateModel(modelForm.id, payload);
        toast.success("已保存");
      }
      setModelDialog(false);
      await refresh();
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "保存失败");
    }
  }

  /** 依次测试该供应商下所有模型，逐个报结果（避免“静默只用第一个模型”的歧义）。 */
  async function testAll(provider: Provider) {
    const list = models.filter((m) => m.providerId === provider.id);
    if (list.length === 0) {
      toast.error("该供应商下还没有模型，先在「模型」页建一个");
      return;
    }
    setTestingId(-provider.id);
    const failures: string[] = [];
    let passed = 0;
    for (const m of list) {
      try {
        const result = await testProvider(provider.id, m.id);
        if (result.ok) {
          passed += 1;
        } else {
          const { raw, hint } = describeFailure(result, m.modelId);
          failures.push(`${m.modelId}：${hint ? `${raw} 💡 ${hint}` : raw}`);
        }
      } catch (err) {
        failures.push(`${m.modelId}：${err instanceof Error ? err.message : "测试失败"}`);
      }
    }
    setTestingId(null);
    if (failures.length === 0) {
      toast.success("全部通过", { description: `${passed}/${list.length} 个模型连通正常` });
    } else {
      toast.error(`${passed}/${list.length} 个模型通过`, {
        description: failures.join("\n\n"),
        duration: 40000,
      });
    }
  }

  async function runTest(provider: Provider, model?: ModelInfo) {
    const fallback = models.find((m) => m.providerId === provider.id);
    const target = model ?? fallback;
    const label = target?.modelId ?? "（该供应商下没有模型）";
    setTestingId(target ? target.id : -provider.id);
    try {
      const result = await testProvider(provider.id, target?.id);
      if (result.ok) {
        toast.success(`连通成功：${label} · ${result.latencyMs} ms`);
      } else {
        const { raw, hint } = describeFailure(result, label);
        toast.error(`连通失败（${label}）`, {
          description: hint ? `${raw}\n\n💡 ${hint}` : raw,
          duration: 30000,
        });
      }
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "测试失败", { duration: 30000 });
    } finally {
      setTestingId(null);
    }
  }

  return (
    <div className="space-y-4 p-6">
      <div className="flex items-center gap-3">
        <div>
          <h1 className="text-xl font-semibold">模型配置</h1>
          <p className="text-muted-foreground text-xs">
            先建供应商（含 api_key），再建模型；api_key 只写入、回读恒为 ****。
          </p>
        </div>
        <Button variant="outline" className="ml-auto" onClick={() => void refresh()} disabled={loading}>
          <RefreshCw className="size-4" /> 刷新
        </Button>
      </div>

      <Tabs defaultValue="providers">
        <TabsList>
          <TabsTrigger value="providers">供应商（{providers.length}）</TabsTrigger>
          <TabsTrigger value="models">模型（{models.length}）</TabsTrigger>
        </TabsList>

        <TabsContent value="providers" className="space-y-3">
          <div className="flex justify-end">
            <Button
              onClick={() => {
                setProviderForm(EMPTY_PROVIDER);
                setProviderDialog(true);
              }}
            >
              <Plus className="size-4" /> 新建供应商
            </Button>
          </div>
          <Card>
            <CardContent className="p-0">
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>名称</TableHead>
                    <TableHead>类型</TableHead>
                    <TableHead>Base URL</TableHead>
                    <TableHead>API Key</TableHead>
                    <TableHead>状态</TableHead>
                    <TableHead className="text-right">操作</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {providers.map((p) => (
                    <TableRow key={p.id}>
                      <TableCell className="font-medium">{p.name}</TableCell>
                      <TableCell>
                        <Badge variant="outline">{p.providerType}</Badge>
                      </TableCell>
                      <TableCell className="max-w-[240px] truncate font-mono text-xs">
                        {p.baseUrl ?? "-"}
                      </TableCell>
                      <TableCell className="font-mono text-xs">{p.apiKey ?? "-"}</TableCell>
                      <TableCell>
                        <Badge variant={p.status === "ENABLED" ? "secondary" : "outline"}>
                          {p.status}
                        </Badge>
                      </TableCell>
                      <TableCell className="text-right">
                        <div className="flex justify-end gap-1">
                          <Button
                            size="sm"
                            variant="outline"
                            disabled={
                              testingId != null ||
                              models.filter((m) => m.providerId === p.id).length === 0
                            }
                            title={
                              models.filter((m) => m.providerId === p.id).length > 0
                                ? "依次测试该供应商下所有模型（单模型测试在「模型」页每行）"
                                : "该供应商下还没有模型"
                            }
                            onClick={() => void testAll(p)}
                          >
                            <PlugZap className="size-3.5" />
                            {testingId === -p.id
                              ? "测试中…"
                              : `测试全部（${models.filter((m) => m.providerId === p.id).length}）`}
                          </Button>
                          <Button
                            size="icon"
                            variant="ghost"
                            title="编辑"
                            onClick={() => {
                              setProviderForm({
                                id: p.id,
                                name: p.name,
                                providerType: p.providerType,
                                baseUrl: p.baseUrl ?? "",
                                apiKey: "",
                                status: p.status,
                              });
                              setProviderDialog(true);
                            }}
                          >
                            <Pencil className="size-3.5" />
                          </Button>
                          <Button
                            size="icon"
                            variant="ghost"
                            title="删除"
                            onClick={async () => {
                              if (!confirm(`删除供应商「${p.name}」？`)) return;
                              try {
                                await deleteProvider(p.id);
                                toast.success("已删除");
                                await refresh();
                              } catch (err) {
                                toast.error(err instanceof Error ? err.message : "删除失败");
                              }
                            }}
                          >
                            <Trash2 className="size-3.5" />
                          </Button>
                        </div>
                      </TableCell>
                    </TableRow>
                  ))}
                  {providers.length === 0 && (
                    <TableRow>
                      <TableCell colSpan={6} className="text-muted-foreground text-center text-sm">
                        还没有供应商，点「新建供应商」。
                      </TableCell>
                    </TableRow>
                  )}
                </TableBody>
              </Table>
            </CardContent>
          </Card>
        </TabsContent>

        <TabsContent value="models" className="space-y-3">
          <div className="flex justify-end">
            <Button
              onClick={() => {
                setModelForm({ ...EMPTY_MODEL, providerId: providers[0]?.id ?? null });
                setModelDialog(true);
              }}
            >
              <Plus className="size-4" /> 新建模型
            </Button>
          </div>
          <Card>
            <CardContent className="p-0">
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>模型 ID</TableHead>
                    <TableHead>显示名</TableHead>
                    <TableHead>供应商</TableHead>
                    <TableHead>采样 / 长度</TableHead>
                    <TableHead>价格 / 百万</TableHead>
                    <TableHead>状态</TableHead>
                    <TableHead className="text-right">操作</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {models.map((m) => (
                    <TableRow key={m.id}>
                      <TableCell
                        className="max-w-[200px] cursor-pointer truncate font-mono text-xs underline decoration-dotted underline-offset-4"
                        title={m.modelId}
                        onClick={() => {
                          const params = (m.params ?? {}) as Record<string, unknown>;
                          setModelForm({
                            id: m.id,
                            providerId: m.providerId,
                            modelId: m.modelId,
                            displayName: m.displayName ?? "",
                            temperature:
                              m.temperature != null
                                ? String(m.temperature)
                                : params.temperature != null
                                  ? String(params.temperature)
                                  : "",
                            topP: m.topP != null ? String(m.topP) : "",
                            contextWindow:
                              m.contextWindow != null ? String(m.contextWindow) : "",
                            maxTokens:
                              m.maxTokens != null ? String(m.maxTokens) : "",
                            priceInput: m.priceInput != null ? String(m.priceInput) : "",
                            priceOutput: m.priceOutput != null ? String(m.priceOutput) : "",
                            priceCurrency: m.priceCurrency ?? "CNY",
                            status: m.status,
                          });
                          setModelDialog(true);
                        }}
                      >
                        {m.modelId}
                      </TableCell>
                      <TableCell className="max-w-[120px] truncate" title={m.displayName ?? ""}>
                        {m.displayName ?? "-"}
                      </TableCell>
                      <TableCell
                        className="max-w-[120px] truncate text-xs"
                        title={
                          providers.find((p) => p.id === m.providerId)?.name ??
                          String(m.providerId)
                        }
                      >
                        {providers.find((p) => p.id === m.providerId)?.name ?? m.providerId}
                      </TableCell>
                      <TableCell className="font-mono text-xs whitespace-nowrap">
                        <div>温度 {m.temperature ?? "-"} · TopN {m.topP ?? "-"}</div>
                        <div className="text-muted-foreground">
                          上下文 {m.contextWindow ?? "-"} · 输出 {m.maxTokens ?? "-"}
                        </div>
                      </TableCell>
                      <TableCell className="font-mono text-xs whitespace-nowrap">
                        {m.priceInput != null || m.priceOutput != null
                          ? `入 ${m.priceInput ?? "-"} / 出 ${m.priceOutput ?? "-"} ${m.priceCurrency ?? ""}`
                          : "-"}
                      </TableCell>
                      <TableCell>
                        <Badge variant={m.status === "ENABLED" ? "secondary" : "outline"}>
                          {m.status}
                        </Badge>
                      </TableCell>
                      <TableCell className="text-right">
                        <div className="flex justify-end gap-1">
                          <Button
                            size="icon"
                            variant="outline"
                            disabled={testingId != null}
                            aria-label={testingId === m.id ? "测试中" : "测试"}
                            title="用这个模型做连通测试"
                            onClick={() => {
                              const provider = providers.find((p) => p.id === m.providerId);
                              if (provider) void runTest(provider, m);
                            }}
                          >
                            <PlugZap className="size-3.5" />
                          </Button>
                          <Button
                            size="icon"
                            variant="ghost"
                            title="编辑"
                            onClick={() => {
                              const params = (m.params ?? {}) as Record<string, unknown>;
                              setModelForm({
                                id: m.id,
                                providerId: m.providerId,
                                modelId: m.modelId,
                                displayName: m.displayName ?? "",
                                temperature:
                                  m.temperature != null
                                    ? String(m.temperature)
                                    : params.temperature != null
                                      ? String(params.temperature)
                                      : "",
                                topP: m.topP != null ? String(m.topP) : "",
                                contextWindow: m.contextWindow != null ? String(m.contextWindow) : "",
                                maxTokens: m.maxTokens != null ? String(m.maxTokens) : "",
                                priceInput: m.priceInput != null ? String(m.priceInput) : "",
                                priceOutput: m.priceOutput != null ? String(m.priceOutput) : "",
                                priceCurrency: m.priceCurrency ?? "CNY",
                                status: m.status,
                              });
                              setModelDialog(true);
                            }}
                          >
                            <Pencil className="size-3.5" />
                          </Button>
                          <Button
                            size="icon"
                            variant="ghost"
                            title="删除"
                            onClick={async () => {
                              if (!confirm(`删除模型「${m.modelId}」？`)) return;
                              try {
                                await deleteModel(m.id);
                                toast.success("已删除");
                                await refresh();
                              } catch (err) {
                                toast.error(err instanceof Error ? err.message : "删除失败");
                              }
                            }}
                          >
                            <Trash2 className="size-3.5" />
                          </Button>
                        </div>
                      </TableCell>
                    </TableRow>
                  ))}
                  {models.length === 0 && (
                    <TableRow>
                      <TableCell colSpan={6} className="text-muted-foreground text-center text-sm">
                        还没有模型。
                      </TableCell>
                    </TableRow>
                  )}
                </TableBody>
              </Table>
            </CardContent>
          </Card>
        </TabsContent>
      </Tabs>

      {/* 供应商弹窗 */}
      <Dialog open={providerDialog} onOpenChange={setProviderDialog}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{providerForm.id == null ? "新建供应商" : "编辑供应商"}</DialogTitle>
          </DialogHeader>
          <div className="grid gap-3">
            <label className="space-y-1 text-sm">
              名称
              <Input
                value={providerForm.name}
                onChange={(e) => setProviderForm({ ...providerForm, name: e.target.value })}
              />
            </label>
            <label className="space-y-1 text-sm">
              类型
              <select
                className="border-input h-9 w-full rounded-md border bg-transparent px-2 text-sm"
                value={providerForm.providerType}
                onChange={(e) =>
                  setProviderForm({ ...providerForm, providerType: e.target.value as ProviderType })
                }
              >
                {PROVIDER_TYPES.map((t) => (
                  <option key={t} value={t}>
                    {t}
                  </option>
                ))}
              </select>
            </label>
            <label className="space-y-1 text-sm">
              Base URL
              <Input
                value={providerForm.baseUrl}
                onChange={(e) => setProviderForm({ ...providerForm, baseUrl: e.target.value })}
                placeholder="https://your-host/v1"
              />
            </label>
            <label className="space-y-1 text-sm">
              API Key{providerForm.id != null && "（留空表示不修改）"}
              <Input
                type="password"
                value={providerForm.apiKey}
                onChange={(e) => setProviderForm({ ...providerForm, apiKey: e.target.value })}
                placeholder={providerForm.id == null ? "sk-..." : "****"}
              />
            </label>
            <label className="space-y-1 text-sm">
              状态
              <select
                className="border-input h-9 w-full rounded-md border bg-transparent px-2 text-sm"
                value={providerForm.status}
                onChange={(e) =>
                  setProviderForm({
                    ...providerForm,
                    status: e.target.value as "ENABLED" | "DISABLED",
                  })
                }
              >
                <option value="ENABLED">ENABLED</option>
                <option value="DISABLED">DISABLED</option>
              </select>
            </label>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setProviderDialog(false)}>
              取消
            </Button>
            <Button onClick={() => void saveProvider()}>保存</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* 模型弹窗 */}
      <Dialog open={modelDialog} onOpenChange={setModelDialog}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{modelForm.id == null ? "新建模型" : "编辑模型"}</DialogTitle>
          </DialogHeader>
          <div className="grid gap-3">
            <label className="space-y-1 text-sm">
              供应商
              <select
                className="border-input h-9 w-full rounded-md border bg-transparent px-2 text-sm"
                value={modelForm.providerId ?? ""}
                onChange={(e) =>
                  setModelForm({ ...modelForm, providerId: Number(e.target.value) })
                }
              >
                {providers.map((p) => (
                  <option key={p.id} value={p.id}>
                    {p.name}
                  </option>
                ))}
              </select>
            </label>
            <label className="space-y-1 text-sm">
              模型 ID
              <Input
                value={modelForm.modelId}
                onChange={(e) => setModelForm({ ...modelForm, modelId: e.target.value })}
                placeholder="gpt-4o-mini / qwen-plus ..."
              />
            </label>
            <label className="space-y-1 text-sm">
              显示名
              <Input
                value={modelForm.displayName}
                onChange={(e) => setModelForm({ ...modelForm, displayName: e.target.value })}
              />
            </label>
            <div className="grid grid-cols-2 gap-3">
              <label className="space-y-1 text-sm">
                温度 temperature
                <Input
                  value={modelForm.temperature}
                  onChange={(e) => setModelForm({ ...modelForm, temperature: e.target.value })}
                  placeholder="0–2，如 0.7"
                />
              </label>
              <label className="space-y-1 text-sm">
                Top-N（Top-P）
                <Input
                  value={modelForm.topP}
                  onChange={(e) => setModelForm({ ...modelForm, topP: e.target.value })}
                  placeholder="(0,1]，如 0.9"
                />
              </label>
              <label className="space-y-1 text-sm">
                上下文长度（tokens）
                <Input
                  value={modelForm.contextWindow}
                  onChange={(e) => setModelForm({ ...modelForm, contextWindow: e.target.value })}
                  placeholder="如 128000 / 1048576"
                />
              </label>
              <label className="space-y-1 text-sm">
                最大输出 tokens（输出长度）
                <Input
                  value={modelForm.maxTokens}
                  onChange={(e) => setModelForm({ ...modelForm, maxTokens: e.target.value })}
                  placeholder="留空=默认，常见 8192"
                />
              </label>
              <label className="space-y-1 text-sm">
                输入价格 / 百万 token
                <Input
                  value={modelForm.priceInput}
                  onChange={(e) => setModelForm({ ...modelForm, priceInput: e.target.value })}
                  placeholder="如 1.5"
                />
              </label>
              <label className="space-y-1 text-sm">
                输出价格 / 百万 token
                <Input
                  value={modelForm.priceOutput}
                  onChange={(e) => setModelForm({ ...modelForm, priceOutput: e.target.value })}
                  placeholder="如 6"
                />
              </label>
              <label className="space-y-1 text-sm">
                价格货币
                <select
                  className="border-input h-9 w-full rounded-md border bg-transparent px-2 text-sm"
                  value={modelForm.priceCurrency}
                  onChange={(e) => setModelForm({ ...modelForm, priceCurrency: e.target.value })}
                >
                  <option value="CNY">CNY（人民币）</option>
                  <option value="USD">USD（美元）</option>
                </select>
              </label>
            </div>
            <p className="text-muted-foreground -mt-1 text-xs">
              ℹ️ 这五项都会真正生效：温度/Top-N/输出长度随每次请求下发；上下文长度用于校验并决定历史压缩阈值；
              价格用于审计看板按模型算成本（单位＝输入价/输出价「每百万 token」）。
            </p>
            {maxTokensWarning(modelForm.maxTokens) && (
              <p className="rounded-md bg-amber-50 p-2 text-xs text-amber-700">
                ⚠️ {maxTokensWarning(modelForm.maxTokens)}
              </p>
            )}
            <label className="space-y-1 text-sm">
              状态
              <select
                className="border-input h-9 w-full rounded-md border bg-transparent px-2 text-sm"
                value={modelForm.status}
                onChange={(e) =>
                  setModelForm({ ...modelForm, status: e.target.value as "ENABLED" | "DISABLED" })
                }
              >
                <option value="ENABLED">ENABLED</option>
                <option value="DISABLED">DISABLED</option>
              </select>
            </label>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setModelDialog(false)}>
              取消
            </Button>
            <Button onClick={() => void saveModel()}>保存</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
