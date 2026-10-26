import { useCallback, useEffect, useMemo, useState } from "react";
import { toast } from "sonner";
import { Copy, Pencil, Plus, RefreshCw, Send, Trash2, Users } from "lucide-react";
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
import { Input } from "@/components/ui/input";
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
  createChannel,
  deleteChannel,
  listChannelPeers,
  listChannels,
  pushChannelMessage,
  restartChannel,
  setChannelEnabled,
  updateChannel,
  type ChannelAppInfo,
  type ChannelAppPayload,
  type ChannelPeerInfo,
} from "@/api/channels";

/** 钉钉凭据表单（Stream 模式最常用，HTTP 回调模式需要额外填 aesKey）。 */
interface FormState {
  id: number | null;
  channelType: "DINGTALK";
  name: string;
  enabled: boolean;
  agentId: number | null;
  appKey: string;
  appSecret: string;
  robotCode: string;
  mode: "stream" | "http";
  aesKey: string;
}

const EMPTY_FORM: FormState = {
  id: null,
  channelType: "DINGTALK",
  name: "",
  enabled: false,
  agentId: null,
  appKey: "",
  appSecret: "",
  robotCode: "",
  mode: "stream",
  aesKey: "",
};

function toForm(item: ChannelAppInfo): FormState {
  return {
    id: item.id,
    channelType: "DINGTALK",
    name: item.name,
    enabled: Boolean(item.enabled),
    agentId: item.agentId,
    appKey: item.config.appKey ?? "",
    appSecret: item.config.appSecret ?? "", // 回显是 ***，留空即不改
    robotCode: item.config.robotCode ?? "",
    mode: (item.config.mode === "http" ? "http" : "stream") as "stream" | "http",
    aesKey: item.config.aesKey ?? "",
  };
}

function toPayload(form: FormState): ChannelAppPayload {
  const config: Record<string, string> = {
    appKey: form.appKey.trim(),
    robotCode: form.robotCode.trim(),
    mode: form.mode,
  };
  // *** 是回显掩码，原样回传表示"不改密钥"
  if (form.appSecret.trim() && form.appSecret.trim() !== "***") {
    config.appSecret = form.appSecret.trim();
  } else if (form.appSecret.trim() === "***") {
    config.appSecret = "***";
  }
  if (form.aesKey.trim()) {
    config.aesKey = form.aesKey.trim();
  }
  return {
    channelType: form.channelType,
    name: form.name.trim(),
    enabled: form.enabled,
    agentId: form.agentId as number,
    config,
  };
}

function statusBadge(status: ChannelAppInfo["status"]) {
  if (status === "RUNNING") return <Badge className="bg-emerald-600">运行中</Badge>;
  if (status === "ERROR") return <Badge variant="destructive">异常</Badge>;
  return <Badge variant="secondary">已停止</Badge>;
}

/** IM 渠道管理（当前支持钉钉；飞书表结构已预留）。 */
export default function ChannelsPage() {
  const [items, setItems] = useState<ChannelAppInfo[]>([]);
  const [agents, setAgents] = useState<AgentDetail[]>([]);
  const [loading, setLoading] = useState(false);
  const [form, setForm] = useState<FormState | null>(null);
  const [saving, setSaving] = useState(false);
  const [peersOf, setPeersOf] = useState<ChannelAppInfo | null>(null);
  const [peers, setPeers] = useState<ChannelPeerInfo[]>([]);
  const [pushTo, setPushTo] = useState<{ app: ChannelAppInfo; peerKey: string } | null>(null);
  const [pushText, setPushText] = useState("这是一条来自平台的主动推送测试消息。");

  const agentOptions = useMemo(
    () => agents.filter((a) => a.permissionMode !== "REQUIRE_APPROVAL"),
    [agents],
  );

  const reload = useCallback(async () => {
    setLoading(true);
    try {
      const [channels, agentList] = await Promise.all([listChannels(), listAgentDetails(1, 200)]);
      setItems(channels);
      setAgents(agentList.records ?? []);
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "加载渠道失败");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void reload();
  }, [reload]);

  async function save() {
    if (!form) return;
    if (!form.name.trim()) {
      toast.error("请填写渠道名称");
      return;
    }
    if (!form.agentId) {
      toast.error("请选择要绑定的 Agent");
      return;
    }
    if (!form.appKey.trim() || !form.robotCode.trim() || (!form.id && !form.appSecret.trim())) {
      toast.error("请填写 AppKey / AppSecret / RobotCode");
      return;
    }
    setSaving(true);
    try {
      const payload = toPayload(form);
      if (form.id) {
        await updateChannel(form.id, payload);
        toast.success("已保存，渠道会按新配置重载");
      } else {
        await createChannel(payload);
        toast.success("已创建");
      }
      setForm(null);
      await reload();
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "保存失败");
    } finally {
      setSaving(false);
    }
  }

  async function toggle(item: ChannelAppInfo) {
    try {
      await setChannelEnabled(item.id, !item.enabled);
      toast.success(item.enabled ? "已停用" : "已启用（正在启动长连接）");
      await reload();
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "操作失败");
    }
  }

  async function restart(item: ChannelAppInfo) {
    try {
      await restartChannel(item.id);
      toast.success("已触发重载");
      setTimeout(() => void reload(), 1200);
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "重载失败");
    }
  }

  async function remove(item: ChannelAppInfo) {
    if (!window.confirm(`删除渠道「${item.name}」？IM 会话历史仍保留在聊天列表里。`)) return;
    try {
      await deleteChannel(item.id);
      toast.success("已删除");
      await reload();
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "删除失败");
    }
  }

  async function openPeers(item: ChannelAppInfo) {
    setPeersOf(item);
    try {
      setPeers(await listChannelPeers(item.id));
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "加载会话失败");
    }
  }

  async function sendPush() {
    if (!pushTo) return;
    try {
      const result = await pushChannelMessage(pushTo.app.id, pushTo.peerKey, pushText);
      toast.success(`已发送 ${result.sent} 条消息`);
      setPushTo(null);
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "推送失败");
    }
  }

  return (
    <div className="space-y-4 p-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-semibold">IM 渠道</h1>
          <p className="text-muted-foreground mt-1 text-sm">
            把钉钉机器人接到平台 Agent：IM 里发消息即可对话，支持收发图片/文件，也能主动推送。
          </p>
        </div>
        <div className="flex gap-2">
          <Button variant="outline" onClick={() => void reload()} disabled={loading}>
            <RefreshCw className={`mr-2 h-4 w-4 ${loading ? "animate-spin" : ""}`} />
            刷新
          </Button>
          <Button onClick={() => setForm({ ...EMPTY_FORM })}>
            <Plus className="mr-2 h-4 w-4" />
            新增渠道
          </Button>
        </div>
      </div>

      <Card>
        <CardHeader>
          <CardTitle className="text-base">渠道列表</CardTitle>
        </CardHeader>
        <CardContent>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>名称</TableHead>
                <TableHead>类型</TableHead>
                <TableHead>Agent</TableHead>
                <TableHead>状态</TableHead>
                <TableHead>会话数</TableHead>
                <TableHead>启用</TableHead>
                <TableHead className="text-right">操作</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {items.length === 0 && (
                <TableRow>
                  <TableCell colSpan={7} className="text-muted-foreground text-center">
                    还没有渠道。点右上角「新增渠道」接入钉钉机器人。
                  </TableCell>
                </TableRow>
              )}
              {items.map((item) => (
                <TableRow key={item.id}>
                  <TableCell className="font-medium">
                    {item.name}
                    {item.webhookPath && (
                      <button
                        type="button"
                        className="text-muted-foreground hover:text-foreground ml-2 inline-flex items-center"
                        title="复制回调地址"
                        onClick={() => {
                          void navigator.clipboard.writeText(item.webhookPath as string);
                          toast.success("回调地址已复制");
                        }}
                      >
                        <Copy className="h-3.5 w-3.5" />
                      </button>
                    )}
                  </TableCell>
                  <TableCell>{item.channelType === "DINGTALK" ? "钉钉" : "飞书"}</TableCell>
                  <TableCell>{item.agentName ?? item.agentId}</TableCell>
                  <TableCell>
                    {statusBadge(item.status)}
                    {item.lastError && (
                      <div className="text-destructive mt-1 max-w-[240px] truncate text-xs" title={item.lastError}>
                        {item.lastError}
                      </div>
                    )}
                  </TableCell>
                  <TableCell>{item.peerCount}</TableCell>
                  <TableCell>
                    <Button size="sm" variant={item.enabled ? "default" : "outline"} onClick={() => void toggle(item)}>
                      {item.enabled ? "已启用" : "启用"}
                    </Button>
                  </TableCell>
                  <TableCell className="text-right">
                    <div className="flex justify-end gap-1">
                      <Button size="sm" variant="ghost" onClick={() => void openPeers(item)}>
                        <Users className="h-4 w-4" />
                      </Button>
                      <Button size="sm" variant="ghost" onClick={() => void restart(item)}>
                        <RefreshCw className="h-4 w-4" />
                      </Button>
                      <Button size="sm" variant="ghost" onClick={() => setForm(toForm(item))}>
                        <Pencil className="h-4 w-4" />
                      </Button>
                      <Button size="sm" variant="ghost" onClick={() => void remove(item)}>
                        <Trash2 className="h-4 w-4 text-red-500" />
                      </Button>
                    </div>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </CardContent>
      </Card>

      {/* 新增/编辑 */}
      <Dialog open={form !== null} onOpenChange={(open) => !open && setForm(null)}>
        <DialogContent className="max-w-lg">
          <DialogHeader>
            <DialogTitle>{form?.id ? "编辑渠道" : "新增钉钉渠道"}</DialogTitle>
            <DialogDescription>
              钉钉开放平台 → 应用 → 机器人：开启「Stream 模式」，拿到 AppKey / AppSecret / RobotCode。
              模型需要能支持图片时，请在 Agent 里选择多模态模型。
            </DialogDescription>
          </DialogHeader>
          {form && (
            <div className="space-y-3">
              <label className="block text-sm">
                渠道名称
                <Input
                  className="mt-1"
                  value={form.name}
                  onChange={(e) => setForm({ ...form, name: e.target.value })}
                  placeholder="例如：客服机器人"
                />
              </label>
              <label className="block text-sm">
                绑定 Agent（需要人工审批的 Agent 不会出现在这里）
                <select
                  className="border-input bg-background mt-1 h-9 w-full rounded-md border px-2 text-sm"
                  value={form.agentId ?? ""}
                  onChange={(e) => setForm({ ...form, agentId: e.target.value ? Number(e.target.value) : null })}
                >
                  <option value="">请选择</option>
                  {agentOptions.map((agent) => (
                    <option key={agent.id} value={agent.id}>
                      {agent.name}（{agent.agentId}）
                    </option>
                  ))}
                </select>
              </label>
              <label className="block text-sm">
                AppKey
                <Input
                  className="mt-1"
                  value={form.appKey}
                  onChange={(e) => setForm({ ...form, appKey: e.target.value })}
                />
              </label>
              <label className="block text-sm">
                AppSecret{form.id && <span className="text-muted-foreground">（留空或保持 *** 表示不修改）</span>}
                <Input
                  className="mt-1"
                  type="password"
                  value={form.appSecret}
                  onChange={(e) => setForm({ ...form, appSecret: e.target.value })}
                />
              </label>
              <label className="block text-sm">
                RobotCode
                <Input
                  className="mt-1"
                  value={form.robotCode}
                  onChange={(e) => setForm({ ...form, robotCode: e.target.value })}
                />
              </label>
              <label className="block text-sm">
                接入方式
                <select
                  className="border-input bg-background mt-1 h-9 w-full rounded-md border px-2 text-sm"
                  value={form.mode}
                  onChange={(e) => setForm({ ...form, mode: e.target.value as "stream" | "http" })}
                >
                  <option value="stream">Stream 长连接（推荐，不需要公网）</option>
                  <option value="http">HTTP 回调（需要公网可达 + 回调地址）</option>
                </select>
              </label>
              {form.mode === "http" && (
                <label className="block text-sm">
                  AES Key（钉钉后台「加解密」里的 aes_key）
                  <Input
                    className="mt-1"
                    value={form.aesKey}
                    onChange={(e) => setForm({ ...form, aesKey: e.target.value })}
                  />
                </label>
              )}
              <label className="flex items-center gap-2 text-sm">
                <input
                  type="checkbox"
                  checked={form.enabled}
                  onChange={(e) => setForm({ ...form, enabled: e.target.checked })}
                />
                保存后立即启用
              </label>
            </div>
          )}
          <DialogFooter>
            <Button variant="outline" onClick={() => setForm(null)}>
              取消
            </Button>
            <Button onClick={() => void save()} disabled={saving}>
              {saving ? "保存中…" : "保存"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* 会话列表 + 主动推送 */}
      <Dialog open={peersOf !== null} onOpenChange={(open) => !open && setPeersOf(null)}>
        <DialogContent className="max-w-2xl">
          <DialogHeader>
            <DialogTitle>「{peersOf?.name}」的 IM 会话</DialogTitle>
            <DialogDescription>
              每个 IM 会话都对应一条平台聊天记录（Web 聊天列表里能看到，带渠道来源）。
            </DialogDescription>
          </DialogHeader>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>对方</TableHead>
                <TableHead>类型</TableHead>
                <TableHead>最近活跃</TableHead>
                <TableHead className="text-right">操作</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {peers.length === 0 && (
                <TableRow>
                  <TableCell colSpan={4} className="text-muted-foreground text-center">
                    还没有人跟这个机器人聊过。
                  </TableCell>
                </TableRow>
              )}
              {peers.map((peer) => (
                <TableRow key={peer.id}>
                  <TableCell className="max-w-[260px] truncate">{peer.peerName ?? peer.peerKey}</TableCell>
                  <TableCell>{peer.peerKind === "GROUP" ? "群聊" : "单聊"}</TableCell>
                  <TableCell>{peer.lastActiveAt ? new Date(peer.lastActiveAt).toLocaleString() : "-"}</TableCell>
                  <TableCell className="text-right">
                    <Button
                      size="sm"
                      variant="outline"
                      onClick={() => peersOf && setPushTo({ app: peersOf, peerKey: peer.peerKey })}
                    >
                      <Send className="mr-1 h-3.5 w-3.5" />
                      推送
                    </Button>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </DialogContent>
      </Dialog>

      <Dialog open={pushTo !== null} onOpenChange={(open) => !open && setPushTo(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>主动推送</DialogTitle>
            <DialogDescription>机器人会主动给这个会话发一条消息（不需要对方先说话）。</DialogDescription>
          </DialogHeader>
          <textarea
            className="border-input bg-background min-h-[120px] w-full rounded-md border p-2 text-sm"
            value={pushText}
            onChange={(e) => setPushText(e.target.value)}
          />
          <DialogFooter>
            <Button variant="outline" onClick={() => setPushTo(null)}>
              取消
            </Button>
            <Button onClick={() => void sendPush()}>发送</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
