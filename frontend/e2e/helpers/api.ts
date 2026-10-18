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

/**
 * 造一笔**今日的、有价格**的消费。
 *
 * 为什么要它：额度用例原先假设「今天已经花过钱」，而这取决于环境 ——
 * 清过测试数据、或最近跑的都是**没配价格**的桩模型时，今日已用为 0，
 * 那么无论额度设多小都不会超限（`exceeds(0, 0.000001)` 为假），用例就假红（实测踩过）。
 * 所以这里自己造：带价格的模型 + 真跑一轮 → token_usage 里就有了成本 > 0 的今日记录。
 */
export async function seedTodaysCost(request: APIRequestContext): Promise<{ cleanup: () => Promise<void> }> {
  const token = await apiToken(request);
  const headers = { Authorization: `Bearer ${token}` };
  const stamp = Date.now();

  /*
   * 先把自己（admin）的额度恢复成"不限"，再种数据。
   *
   * 为什么必须先做：额度是**用户级全局状态**。若上一次跑在某处中断（或在临时设了极小额度时挂掉），
   * 残留的极小额度会把**种数据这一轮**直接拦掉 —— 于是"今日已用"仍是 0，后面的「已超出」断言
   * 永远不成立。表现就是"只在全量跑偶发红、单独跑绿"，而且会自我延续（实测）。先解锁即可断掉这个环。
   */
  const me = await (await request.get(`${API}/api/auth/me`, { headers })).json();
  const myId = (me.data?.id ?? me.data?.userId) as number;
  if (myId) {
    await request.put(`${API}/api/users/${myId}/quota`, {
      headers,
      data: { dailyQuota: null, monthlyQuota: null, mode: "BLOCK" },
    });
  }
  const provider = await (
    await request.post(`${API}/api/model-providers`, {
      headers,
      data: {
        name: `e2e-quota-prov-${stamp}`,
        providerType: "OPENAI_COMPATIBLE",
        baseUrl: "http://localhost:8099/v1",
        apiKey: "stub-key",
        status: "ENABLED",
      },
    })
  ).json();
  // 价格给 1 元/百万 token：任何一次调用的成本都 > 0，且远大于测试用的极小额度
  const model = await (
    await request.post(`${API}/api/models`, {
      headers,
      data: {
        providerId: provider.data.id,
        modelId: `e2e-quota-model-${stamp}`,
        priceInput: 1,
        priceOutput: 1,
        priceCurrency: "CNY",
        status: "ENABLED",
      },
    })
  ).json();
  const agentName = `e2e-quota-agent-${stamp}`;
  const agent = await (
    await request.post(`${API}/api/agents`, {
      headers,
      data: {
        name: agentName,
        agentId: agentName,
        sysPrompt: "你是助手。",
        modelId: model.data.id,
        permissionMode: "ALWAYS_ALLOW",
        maxIterations: 1,
        status: "ENABLED",
        skillIds: [],
        mcpServerIds: [],
      },
    })
  ).json();
  const session = await (
    await request.post(`${API}/api/sessions`, { headers, data: { agentId: agent.data.id, title: `e2e-quota-sess-${stamp}` } })
  ).json();
  const sessions = await (await request.get(`${API}/api/sessions`, { headers })).json();
  const threadId = (sessions.data as { id: number; threadId: string }[]).find((x) => x.id === session.data.id)!.threadId;
  // 真跑一轮（用桩），让 token_usage 落一条今日记录
  const runResp = await request.post(`${API}/agui/run`, {
    headers,
    data: {
      threadId,
      runId: `quota-seed-${stamp}`,
      messages: [{ id: "m1", role: "user", content: "你好" }],
      forwardedProps: { agentId: agentName },
    },
  });
  const runText = await runResp.text();

  /*
   * 校验"确实种下了"：这条会话的用量记录必须出现。
   *
   * 为什么必须校验：原先只发一次 run 就返回，若那一轮被额度拦掉/出错，调用方完全不知情，
   * 最终以"某处超时"的形式失败 —— 排查成本极高。这里直接给出可读原因。
   */
  let seeded = false;
  for (let i = 0; i < 20 && !seeded; i += 1) {
    const page = await (
      await request.get(`${API}/api/audit/token/records?current=1&size=20`, { headers })
    ).json();
    const rows = (page.data?.records ?? []) as { sessionId: number | null }[];
    seeded = rows.some((r) => r.sessionId === session.data.id);
    if (!seeded) {
      await new Promise((r) => setTimeout(r, 300));
    }
  }
  if (!seeded) {
    throw new Error(
      `seedTodaysCost 失败：这一轮没有落 token_usage（sessionId=${session.data.id}）。` +
        `run 返回片段=${runText.slice(0, 200)}`,
    );
  }
  return {
    cleanup: async () => {
      await request.delete(`${API}/api/sessions/${session.data.id}`, { headers });
      await request.delete(`${API}/api/agents/${agent.data.id}`, { headers });
      await request.delete(`${API}/api/models/${model.data.id}`, { headers });
      await request.delete(`${API}/api/model-providers/${provider.data.id}`, { headers });
    },
  };
}
