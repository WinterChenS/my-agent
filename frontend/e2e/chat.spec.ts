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
  // 桩的回复 = "stub-ok <modelId>"（便于测试精确知道期望文本）
  return { agentId, providerId: provider.data.id, expectedReply: `stub-ok e2e-chat-model-${stamp}` };
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
  const { agentId, expectedReply } = await createStubAgent(request, Date.now());
  const input = await loginAndSelectAgent(page, agentId);

  await input.fill("你好，请回复");

  // 先开始采样再回车：流式窗口可能很短，等断言完再采样会漏掉中间态（曾经因此偶发失败）
  // 采样必须用 evaluate 直接读 DOM：locator.innerText() 会“自动等待”，
  // 在流式窗口很短时会把中间态一次性跳过（曾经因此偶发只采到 1 个状态）
  const readAssistant = () =>
    page.evaluate(() => {
      const all = document.querySelectorAll('[data-testid="assistant-text"]');
      const last = all[all.length - 1];
      return last ? (last.textContent ?? "") : "";
    });
  const snapshots: string[] = [];
  const sampling = (async () => {
    const deadline = Date.now() + 40_000;
    while (Date.now() < deadline) {
      const text = (await readAssistant().catch(() => "")) ?? "";
      if (text && text !== snapshots[snapshots.length - 1]) snapshots.push(text);
      if (text.includes("stub-ok") && text === expectedReply) return;
      await page.waitForTimeout(10);
      void deadline;
    }
  })();

  await input.press("Enter");

  // 用户气泡立即出现（用 testid 断言，不依赖文本——模型回复里也可能出现同样内容）
  await expect(page.locator('[data-testid="user-text"]').last()).toHaveText("你好，请回复");
  await sampling;
  expect(snapshots.length, `采样到的流式中间态: ${JSON.stringify(snapshots)}`).toBeGreaterThanOrEqual(2);
  expect(snapshots[snapshots.length - 1]).toBe(expectedReply);

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
  // 跑的时候输入框是禁用态（contenteditable=false），先等它恢复
  await expect(input).toHaveAttribute("contenteditable", "true", { timeout: 60_000 });
  await input.fill("第二句");
  await input.press("Enter");
  await expect(page.locator('[data-testid="user-text"]')).toHaveCount(2, { timeout: 20_000 });
  await expect(page.locator('[data-testid="assistant-text"]')).toHaveCount(2, { timeout: 60_000 });

  const userBubbles = page.locator('[data-testid="user-text"]');
  await expect(userBubbles.nth(0)).toHaveText("第一句");
  await expect(userBubbles.nth(1)).toHaveText("第二句");

  await page.screenshot({ path: "../docs/acceptance/chat-bubbles.png", fullPage: true });
});

test("布局：会话历史在菜单栏下半部分；输入框与消息区同宽同位置", async ({ page, request }) => {
  // 用一个宽窗口，否则看不出“太宽”的问题
  await page.setViewportSize({ width: 1920, height: 1080 });
  const { agentId } = await createStubAgent(request, Date.now());
  const input = await loginAndSelectAgent(page, agentId);

  // 先发一条：欢迎页下消息区是隐藏的，要量布局得先真正开始一段对话
  await input.fill("布局检查");
  await input.press("Enter");
  await expect(page.getByTestId("chat-column")).toBeVisible({ timeout: 30_000 });

  // ① 会话历史驻在**菜单栏下半部分**（在菜单项下方）
  const navBox = (await page.locator("aside nav").first().boundingBox())!;
  const listBox = (await page.getByTestId("session-list").boundingBox())!;
  expect(listBox.y, "会话历史应位于菜单项下方").toBeGreaterThanOrEqual(
    navBox.y + navBox.height - 1,
  );

  const section = page.locator("main > div > section");
  const sBox = (await section.boundingBox())!;
  const sCenter = sBox.x + sBox.width / 2;

  const msgCol = page.locator('[data-testid="chat-column"]');
  const msgBox = (await msgCol.boundingBox())!;
  // 量**输入框本体**（圆角外框）：它应与消息列里气泡的可用宽度一致。
  // 消息列是 max-w-3xl + p-4（左右各 16），气泡可用宽度 = 768 - 32 = 736；
  // 输入框外层同样 max-w-3xl + px-4，所以它的边框正好与气泡左右边缘对齐。
  const inputBox = page.locator('[data-testid="composer"] > div > div').first();
  const inBox = (await inputBox.boundingBox())!;

  // 收窄：远小于可用宽度（max-w-3xl = 768）
  expect(msgBox.width, "消息区应明显窄于可用宽度").toBeLessThan(sBox.width * 0.7);
  expect(Math.round(msgBox.width)).toBe(768);

  // 居中：两者中心与所在区域中心一致（容差 2px）
  expect(Math.abs(sCenter - (msgBox.x + msgBox.width / 2))).toBeLessThanOrEqual(2);
  expect(Math.abs(sCenter - (inBox.x + inBox.width / 2))).toBeLessThanOrEqual(2);

  // 输入框宽度 = 消息列内容宽度（气泡可用宽度）
  expect(Math.round(inBox.width)).toBe(Math.round(msgBox.width) - 32);

  await page.screenshot({ path: "../docs/acceptance/chat-layout-wide.png" });
});

test("后端不可用时显示错误态且不白屏", async ({ page }) => {
  await page.goto("/login");
  await page.getByLabel("密码").fill("Admin@123");
  await page.getByRole("button", { name: "登录" }).click();
  await expect(page).toHaveURL(/\/chat/, { timeout: 15_000 });

  // 先等界面就绪（聊天页是按需加载的 chunk），**再**断网模拟后端不可用。
  // 顺序反了会偶发：在全量跑时输入框可能还没渲染出来，而 fill 等不到就超时（实测）
  const input = page.getByPlaceholder("输入消息，回车发送");
  await expect(input).toBeVisible({ timeout: 30_000 });

  // 断掉所有 /api 与 /agui 请求，模拟后端不可用
  await page.route("**/api/**", (route) => route.abort());
  await page.route("**/agui/**", (route) => route.abort());

  await input.fill("这条应该失败");
  await input.press("Enter");

  // 页面不能白屏：侧边栏仍在（用精确匹配，避免与模型回复里的项目路径 D:\project\my-agent 撞车）
  await expect(page.getByText("my-agent", { exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "重试" })).toBeVisible({ timeout: 20_000 });
  // 失败时不能把用户输入弄丢
  // 输入框是 contentEditable：断言文本
  await expect(input).toHaveText("这条应该失败");

  await page.screenshot({ path: "../docs/acceptance/task-4.3-chat-error-state.png", fullPage: true });
});
