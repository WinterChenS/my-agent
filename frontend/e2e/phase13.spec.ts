import { expect, test, type APIRequestContext, type Page } from "@playwright/test";

/**
 * Phase 13 浏览器验收：会话列表按时间分组 + 输入框支持拖拽/粘贴附件。
 *
 * 自给自足：自己造 e2e- 前缀的 provider/model/agent，跑完清理。
 */

const API = "http://localhost:8080";
const STUB = "http://localhost:8099/v1";
const PREFIX = "e2e-p13-";

async function apiToken(request: APIRequestContext): Promise<string> {
  const res = await request.post(`${API}/api/auth/login`, {
    data: { username: "admin", password: "Admin@123" },
  });
  return (await res.json()).data.token as string;
}

async function createStubAgent(request: APIRequestContext, stamp: number) {
  const token = await apiToken(request);
  const headers = { Authorization: `Bearer ${token}` };
  const agentId = `${PREFIX}agent-${stamp}`;
  const provider = await (
    await request.post(`${API}/api/model-providers`, {
      headers,
      data: {
        name: `${PREFIX}prov-${stamp}`,
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
      data: { providerId: provider.data.id, modelId: `${PREFIX}model-${stamp}`, status: "ENABLED" },
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
      maxIterations: 3,
      status: "ENABLED",
      skillIds: [],
      mcpServerIds: [],
    },
  });
  return { agentId, headers };
}

async function apiCleanup(request: APIRequestContext) {
  const token = await apiToken(request);
  const headers = { Authorization: `Bearer ${token}` };
  const del = async (path: string, id: number) =>
    request.delete(`${API}${path}/${id}`, { headers, failOnStatusCode: false });

  const providers = await (
    await request.get(`${API}/api/model-providers?current=1&size=500`, { headers })
  ).json();
  const providerIds = new Set<number>(
    (providers.data?.records ?? [])
      .filter((p: { name: string }) => String(p.name).startsWith(PREFIX))
      .map((p: { id: number }) => p.id),
  );
  const agents = await (await request.get(`${API}/api/agents?current=1&size=500`, { headers })).json();
  const agentIds = new Set<number>();
  for (const a of agents.data?.records ?? []) {
    if (String(a.agentId).startsWith(PREFIX)) {
      agentIds.add(a.id);
      await del("/api/agents", a.id);
    }
  }
  const sessions = await (await request.get(`${API}/api/sessions`, { headers })).json();
  for (const s of sessions.data ?? []) {
    if (agentIds.has(Number(s.agentId))) await del("/api/sessions", s.id);
  }
  const models = await (await request.get(`${API}/api/models?current=1&size=500`, { headers })).json();
  for (const m of models.data?.records ?? []) {
    if (String(m.modelId).startsWith(PREFIX) || providerIds.has(Number(m.providerId))) {
      await del("/api/models", m.id);
    }
  }
  for (const id of providerIds) await del("/api/model-providers", id);
}

test.afterEach(async ({ request }) => {
  await apiCleanup(request);
});

async function loginAndSelectAgent(page: Page, agentName: string) {
  await page.goto("/login");
  await page.getByLabel("密码").fill("Admin@123");
  await page.getByRole("button", { name: "登录" }).click();
  await expect(page).toHaveURL(/\/chat/, { timeout: 15_000 });
  await page.getByTitle("新建会话").click();
  const agentSelect = page.locator("select");
  await expect(agentSelect).toContainText(agentName, { timeout: 20_000 });
  await agentSelect.selectOption({ label: agentName });
  return page.getByPlaceholder("输入消息，回车发送");
}

test("会话列表按时间分组显示", async ({ page }) => {
  await page.goto("/login");
  await page.getByLabel("密码").fill("Admin@123");
  await page.getByRole("button", { name: "登录" }).click();
  await expect(page).toHaveURL(/\/chat/, { timeout: 15_000 });

  // 至少要有「今天」这一组（会话时间来自后端，最近活跃的必然落在今天或更早的分组）
  const groups = page.locator('[data-testid="session-group"]');
  await expect(groups.first()).toBeVisible({ timeout: 20_000 });
  const labels = await groups.allTextContents();
  expect(labels.length).toBeGreaterThan(0);
  expect(labels.some((label) => ["今天", "昨天", "最近 7 天", "最近 30 天", "更早"].includes(label.trim()))).toBe(
    true,
  );
  await page.screenshot({ path: "../docs/acceptance/task-13-session-groups.png", fullPage: true });
});

test("把文件拖进聊天区即成为待发送附件", async ({ page, request }) => {
  const stamp = Date.now();
  const { agentId } = await createStubAgent(request, stamp);
  await loginAndSelectAgent(page, agentId);

  // 在页面里造一个真实 DataTransfer + File，然后派发 drop
  await page.evaluate(() => {
    const section = document.querySelector("section") as HTMLElement;
    const dataTransfer = new DataTransfer();
    dataTransfer.items.add(new File(["hello drop"], "拖拽测试.md", { type: "text/markdown" }));
    section.dispatchEvent(new DragEvent("dragover", { bubbles: true, dataTransfer }));
    section.dispatchEvent(new DragEvent("drop", { bubbles: true, dataTransfer }));
  });

  const chips = page.locator('[data-testid="attachment-chip"]');
  await expect(chips).toHaveCount(1);
  await expect(chips.first()).toContainText("拖拽测试.md");
  // 仍是暂存（发送前不产生来源文件记录）——清单弹窗里还是空的
  await page.getByTestId("open-artifact-browser").click();
  await expect(page.getByTestId("artifact-browser-empty")).toBeVisible();
  await expect(page.locator('[data-testid="panel-sources-item"]')).toHaveCount(0);
  await page.keyboard.press("Escape");

  await page.screenshot({ path: "../docs/acceptance/task-13-drag-drop.png", fullPage: true });
});

test("拖拽经过时显示落区提示，离开后消失", async ({ page, request }) => {
  const stamp = Date.now();
  const { agentId } = await createStubAgent(request, stamp);
  await loginAndSelectAgent(page, agentId);

  await page.evaluate(() => {
    const section = document.querySelector("section") as HTMLElement;
    const dataTransfer = new DataTransfer();
    dataTransfer.items.add(new File(["x"], "a.txt", { type: "text/plain" }));
    section.dispatchEvent(new DragEvent("dragover", { bubbles: true, dataTransfer }));
  });
  await expect(page.locator('[data-testid="drop-hint"]')).toBeVisible();

  await page.evaluate(() => {
    const section = document.querySelector("section") as HTMLElement;
    section.dispatchEvent(new DragEvent("dragleave", { bubbles: true }));
  });
  await expect(page.locator('[data-testid="drop-hint"]')).toHaveCount(0);
});
