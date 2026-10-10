import { expect, test, type APIRequestContext, type Page } from "@playwright/test";

/**
 * 聊天页浏览器验收（Task 4.3 + 多轮回归）。
 *
 * 设计原则（踩过坑）：**测试自给自足**——自己用 API 造一个指向本地桩的 Agent，
 * 不依赖用户维护的 Agent/模型（用户改了演示 Agent 的模型后，旧用例就红了），
 * 也不依赖外部真实模型的速度。结束用 afterEach 清理自己造的数据。
 */

const API = "http://localhost:8080";
const STUB = "http://localhost:8099/v1";

async function apiToken(request: APIRequestContext): Promise<string> {
  const res = await request.post(`${API}/api/auth/login`, {
    data: { username: "admin", password: "Admin@123" },
  });
  return (await res.json()).data.token as string;
}

/** 造一个指向本地桩的 provider/model/agent（名字统一带 e2e- 前缀，便于清理）。 */
async function createStubAgent(request: APIRequestContext, stamp: number) {
  const token = await apiToken(request);
  const headers = { Authorization: `Bearer ${token}` };
  const providerName = `e2e-chat-prov-${stamp}`;
  const agentId = `e2e-chat-agent-${stamp}`;

  const provider = await (
    await request.post(`${API}/api/model-providers`, {
      headers,
      data: {
        name: providerName,
        providerType: "OPENAI_COMPATIBLE",
        baseUrl: STUB,
        apiKey: "sk-e2e",
        status: "ENABLED",
      },
    })
  ).json();
  const model = await (
    await request.post(`${API}/api/models`, {
      headers,
      data: { providerId: provider.data.id, modelId: `e2e-chat-model-${stamp}`, status: "ENABLED" },
    })
  ).json();
  await request.post(`${API}/api/agents`, {
    headers,
    data: {
      name: agentId,
      agentId,
      sysPrompt: "你是测试助手。",
      modelId: model.data.id,
      permissionMode: "ALWAYS_ALLOW",
      maxIterations: 5,
      status: "ENABLED",
      skillIds: [],
      mcpServerIds: [],
    },
  });
  return { agentId, providerId: provider.data.id };
}

/** 清理本次测试造的数据（含这些 Agent 产生的会话）。 */
async function apiCleanup(request: APIRequestContext, prefix: string) {
  const token = await apiToken(request);
  const headers = { Authorization: `Bearer ${token}` };
  const del = async (path: string, id: number) =>
    request.delete(`${API}${path}/${id}`, { headers, failOnStatusCode: false });

  const providers = await (
    await request.get(`${API}/api/model-providers?current=1&size=500`, { headers })
  ).json();
  const providerIds = new Set<number>(
    (providers.data?.records ?? [])
      .filter((p: { name: string }) => String(p.name).startsWith(prefix))
      .map((p: { id: number }) => p.id),
  );

  const agents = await (await request.get(`${API}/api/agents?current=1&size=500`, { headers })).json();
  const agentIds = new Set<number>();
  for (const a of agents.data?.records ?? []) {
    if (String(a.agentId).startsWith(prefix)) {
      agentIds.add(a.id);
      await del("/api/agents", a.id);
    }
  }

  // 这些 Agent 产生的会话（否则会一直在左侧列表堆积）
  const sessions = await (await request.get(`${API}/api/sessions`, { headers })).json();
  for (const s of sessions.data ?? []) {
    if (agentIds.has(Number(s.agentId))) await del("/api/sessions", s.id);
  }

  const models = await (await request.get(`${API}/api/models?current=1&size=500`, { headers })).json();
  for (const m of models.data?.records ?? []) {
    if (String(m.modelId).startsWith(prefix) || providerIds.has(Number(m.providerId))) {
      await del("/api/models", m.id);
    }
  }
  for (const id of providerIds) await del("/api/model-providers", id);
}

test.afterEach(async ({ request }) => {
  await apiCleanup(request, "e2e-");
});

async function loginAndSelectAgent(page: Page, agentName: string) {
  await page.goto("/login");
  await page.getByLabel("密码").fill("Admin@123");
  await page.getByRole("button", { name: "登录" }).click();
  await expect(page).toHaveURL(/\/chat/, { timeout: 15_000 });

  // 刷新恢复的是历史会话；新建会话才能重新选 Agent
  await page.getByTitle("新建会话").click();
  const agentSelect = page.locator("select");
  await expect(agentSelect).toContainText(agentName, { timeout: 20_000 });
  await agentSelect.selectOption({ label: agentName });
  return page.getByPlaceholder("输入消息，回车发送");
}

test("登录 → 发消息 → 流式气泡 → 刷新回放", async ({ page, request }) => {
  const { agentId } = await createStubAgent(request, Date.now());
  const input = await loginAndSelectAgent(page, agentId);

  await input.fill("你好，请回复");
  await input.press("Enter");

  // 用户气泡立即出现（用 testid 断言，不依赖文本——模型回复里也可能出现同样内容）
  await expect(page.locator('[data-testid="user-text"]').last()).toHaveText("你好，请回复");

  // 采样助手气泡：至少 2 个不同中间态 => 确实是逐字流式
  const assistant = page.locator('[data-testid="assistant-text"]').last();
  const snapshots: string[] = [];
  const deadline = Date.now() + 40_000;
  while (Date.now() < deadline) {
    const text = (await assistant.innerText().catch(() => "")) ?? "";
    if (text && text !== snapshots[snapshots.length - 1]) snapshots.push(text);
    if (text.includes("stub-ok")) break;
    await page.waitForTimeout(30);
  }
  expect(snapshots.length, `采样到的流式中间态: ${JSON.stringify(snapshots)}`).toBeGreaterThanOrEqual(2);
  expect(snapshots[snapshots.length - 1]).toContain("stub-ok");

  await page.screenshot({ path: "../docs/acceptance/task-4.3-chat-streaming.png", fullPage: true });

  // 刷新后历史仍在（回放出来的气泡同样带 testid）
  await page.reload();
  await expect(page.locator('[data-testid="user-text"]').first()).toHaveText("你好，请回复", {
    timeout: 20_000,
  });
  await expect(page.locator('[data-testid="assistant-text"]').first()).toContainText("stub-ok", {
    timeout: 20_000,
  });

  await page.screenshot({ path: "../docs/acceptance/task-4.3-chat-history.png", fullPage: true });
});

test("多轮对话：发第二条不会清空之前的对话（回归）", async ({ page, request }) => {
  const { agentId } = await createStubAgent(request, Date.now());
  const input = await loginAndSelectAgent(page, agentId);

  // 第一轮
  await input.fill("第一句");
  await input.press("Enter");
  await expect(page.locator('[data-testid="user-text"]')).toHaveCount(1, { timeout: 20_000 });
  await expect(page.locator('[data-testid="assistant-text"]')).toHaveCount(1, { timeout: 60_000 });

  // 第二轮：第一轮的气泡必须还在
  await input.fill("第二句");
  await input.press("Enter");
  await expect(page.locator('[data-testid="user-text"]')).toHaveCount(2, { timeout: 20_000 });
  await expect(page.locator('[data-testid="assistant-text"]')).toHaveCount(2, { timeout: 60_000 });

  const userBubbles = page.locator('[data-testid="user-text"]');
  await expect(userBubbles.nth(0)).toHaveText("第一句");
  await expect(userBubbles.nth(1)).toHaveText("第二句");

  await page.screenshot({ path: "../docs/acceptance/chat-bubbles.png", fullPage: true });
});

test("后端不可用时显示错误态且不白屏", async ({ page }) => {
  await page.goto("/login");
  await page.getByLabel("密码").fill("Admin@123");
  await page.getByRole("button", { name: "登录" }).click();
  await expect(page).toHaveURL(/\/chat/, { timeout: 15_000 });

  // 断掉所有 /api 与 /agui 请求，模拟后端不可用
  await page.route("**/api/**", (route) => route.abort());
  await page.route("**/agui/**", (route) => route.abort());

  const input = page.getByPlaceholder("输入消息，回车发送");
  await input.fill("这条应该失败");
  await input.press("Enter");

  // 页面不能白屏：侧边栏、输入框仍在，且有错误提示与重试入口
  await expect(page.getByText("my-agent")).toBeVisible();
  await expect(page.getByRole("button", { name: "重试" })).toBeVisible({ timeout: 20_000 });
  // 失败时不能把用户输入弄丢
  await expect(input).toHaveValue("这条应该失败");

  await page.screenshot({ path: "../docs/acceptance/task-4.3-chat-error-state.png", fullPage: true });
});
