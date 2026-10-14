import { expect, type APIRequestContext } from "@playwright/test";

export const API = "http://localhost:8080";

/** 登录拿 admin token（多数用例需要直接调接口造/清数据）。 */
export async function apiToken(request: APIRequestContext): Promise<string> {
  const res = await request.post(`${API}/api/auth/login`, {
    data: { username: "admin", password: "Admin@123" },
  });
  return (await res.json()).data.token as string;
}

/** 记下当前所有会话 id，用于收尾时只删「本次测试新建的」。 */
export async function snapshotSessionIds(request: APIRequestContext): Promise<Set<number>> {
  const token = await apiToken(request);
  const res = await request.get(`${API}/api/sessions`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  const body = await res.json();
  return new Set<number>((body.data as { id: number }[]).map((s) => s.id));
}

/**
 * 删掉本次测试新建的会话。
 *
 * 为什么需要：这些用例会真的新建会话（`/` 选技能、后台通知、新建会话等），
 * 不清理的话会**把侧边栏塞满并挤掉真实数据** —— 实测已经导致「扫会话找代码块」的用例变红，
 * 也让你看到的会话列表里混着一堆测试标题。
 */
export async function deleteNewSessions(
  request: APIRequestContext,
  before: Set<number>,
): Promise<void> {
  const token = await apiToken(request);
  const headers = { Authorization: `Bearer ${token}` };
  const res = await request.get(`${API}/api/sessions`, { headers });
  const body = await res.json();
  for (const s of body.data as { id: number }[]) {
    if (!before.has(s.id)) {
      await request.delete(`${API}/api/sessions/${s.id}`, { headers });
    }
  }
}

/**
 * 造一个指向本地桩的 provider/model/agent（名字带 `e2e-` 前缀，便于清理）。
 *
 * 为什么要自给自足：这些用例原本靠"页面上正好有个能回话的 Agent"（库里默认那个用的是
 * 用户的真实模型）。一旦用户的模型/网关不可用（实测：网关把模型关掉后全 404），
 * 用例就集体变红 —— 而那是环境问题，不该算测试失败。
 */
export async function createStubAgent(
  request: APIRequestContext,
  stamp: number,
  opts: { modelId?: string } = {},
): Promise<{ agentName: string; agentDbId: number; headers: Record<string, string> }> {
  const token = await apiToken(request);
  const headers = { Authorization: `Bearer ${token}` };
  const provider = await (
    await request.post(`${API}/api/model-providers`, {
      headers,
      data: {
        name: `e2e-prov-${stamp}`,
        providerType: "OPENAI_COMPATIBLE",
        baseUrl: "http://localhost:8099/v1",
        apiKey: "stub-key",
        status: "ENABLED",
      },
    })
  ).json();
  const model = await (
    await request.post(`${API}/api/models`, {
      headers,
      data: {
        providerId: provider.data.id,
        modelId: opts.modelId ?? `stub-model-${stamp}`,
        status: "ENABLED",
      },
    })
  ).json();
  const agentName = `e2e-agent-${stamp}`;
  const agent = await (
    await request.post(`${API}/api/agents`, {
      headers,
      data: {
        name: agentName,
        agentId: agentName,
        sysPrompt: "你是助手。",
        modelId: model.data.id,
        permissionMode: "ALWAYS_ALLOW",
        maxIterations: 2,
        status: "ENABLED",
        skillIds: [],
        mcpServerIds: [],
      },
    })
  ).json();
  return { agentName, agentDbId: agent.data.id as number, headers };
}

/** 删掉上面造的 agent/provider/model（provider/model 由外键约束，只在没有引用时才会删掉）。 */
export async function deleteStubAgent(
  request: APIRequestContext,
  headers: Record<string, string>,
  agentDbId: number,
): Promise<void> {
  const agents = await (await request.get(`${API}/api/agents/${agentDbId}`, { headers })).json();
  const modelId = agents?.data?.modelId as number | undefined;
  await request.delete(`${API}/api/agents/${agentDbId}`, { headers });
  if (modelId != null) {
    const model = await (await request.get(`${API}/api/models/${modelId}`, { headers })).json();
    const providerId = model?.data?.providerId as number | undefined;
    await request.delete(`${API}/api/models/${modelId}`, { headers });
    if (providerId != null) {
      await request.delete(`${API}/api/model-providers/${providerId}`, { headers });
    }
  }
}

/** 登录并选中指定名字的 Agent（会先点「新建会话」才有选择器）。 */
export async function loginAndSelectAgent(
  page: import("@playwright/test").Page,
  agentName: string,
): Promise<import("@playwright/test").Locator> {
  await page.goto("/login");
  await page.getByLabel("密码").fill("Admin@123");
  await page.getByRole("button", { name: "登录" }).click();
  await page.waitForURL(/\/chat/, { timeout: 20_000 });
  await page.waitForSelector("[data-testid='chat-input']", { timeout: 30_000 });
  await page.getByTitle("新建会话").click();
  const select = page.locator("select");
  await expect(select).toContainText(agentName, { timeout: 20_000 });
  await select.selectOption({ label: agentName });
  return page.getByTestId("chat-input");
}
