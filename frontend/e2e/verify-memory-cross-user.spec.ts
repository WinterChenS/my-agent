import { rmSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { expect, test, type APIRequestContext, type Page } from "@playwright/test";
import { API, apiToken, createStubAgent, deleteStubAgent } from "./helpers/api";
import { startToolStub } from "./helpers/toolStub";

/**
 * 记忆的跨用户权限：**超管能按用户名 / 中文显示名 / ID 搜到别人并看其记忆，普通用户只能看自己的**。
 *
 * <p>这条用例刻意“真”：不拿接口硬塞别人的记忆，而是
 *  ① 有一个**真实的普通账号**；② 用浏览器**真的登录它**；③ 让它跟 Agent 聊一轮，
 *  由模型调 `memory_save` 把内容写进**它自己的** `MEMORY.md`；
 *  ④ 再以超管身份在页面上**搜到它**并看到那份记忆。
 * 这样验证的是“别人自己产生的数据，超管能不能检索到”，而不是“接口能不能被调通”。
 *
 * <p>模型用的是**专为本用例拉起的桩**（:8098，配置成会调 `memory_save`）：
 * 真实模型的额度是环境问题，不该决定这条权限用例的红绿。
 *
 * <p>关于“对端账号”为什么**不删**：平台把 `token_usage` 当审计数据，删用户时外键会拦住
 * （`token_usage_user_id_fkey`，属设计而非 bug）。所以这里用一个固定的对端账号
 * `e2e-mem-peer`：每轮开始先把它自己的记忆与工作区清空、会话删掉，账号本身留着复用 ——
 * 既不会越跑越脏，也不会为了跑测试去删审计数据。要彻底移除它，只能直接操作数据库。
 */
// 默认配置里没有 actionTimeout：点击/输入卡住时会**一直等**，把整条用例的时间耗光还看不出卡在哪
// （实测：这一步让 600s 全花在一个等不到的元素上）。这里给动作加个上限，卡住就快速报错。
test.use({ actionTimeout: 20_000 });

const HERE = dirname(fileURLToPath(import.meta.url));
/** 建议端口；真被占了 `startToolStub` 会自己往后找，并用它返回的 `port` */
const TOOL_STUB_PORT = 8098;
const PEER_USERNAME = "e2e-mem-peer";
const PEER_DISPLAY_NAME = "E2E记忆对端";
const PEER_PASSWORD = "Passw0rd!";

async function login(page: Page, username: string, password: string) {
  await page.goto("/login");
  await page.getByLabel("用户名").fill(username);
  await page.getByLabel("密码").fill(password);
  await page.getByRole("button", { name: /登录/ }).click();
  await page.waitForURL(/\/chat/, { timeout: 20_000 });
}

/** 在记忆页按关键字搜人并选中第一个结果。 */
async function searchAndPick(page: Page, keyword: string) {
  const box = page.getByTestId("memory-user-search-input");
  await box.fill(keyword);
  console.log(`[记忆权限] 搜索框已填入：${keyword}`);
  const result = page.getByTestId("memory-user-result").first();
  await expect(result).toBeVisible({ timeout: 20_000 });
  await result.click();
  await expect(page.getByTestId("memory-viewing-other")).toBeVisible({ timeout: 20_000 });
}

async function memoryOf(request: APIRequestContext, headers: Record<string, string>, userId: number) {
  const res = await request.get(`${API}/api/memory/MEMORY.md?userId=${userId}`, { headers });
  if (!res.ok()) return "";
  const body = await res.json();
  return (body.data?.content ?? "") as string;
}

/** 找到固定的对端账号；没有就建一个（显示名带中文，模拟真实数据）。 */
async function ensurePeer(request: APIRequestContext, headers: Record<string, string>) {
  const found = await (
    await request.get(`${API}/api/users?size=20&keyword=${PEER_USERNAME}`, { headers })
  ).json();
  const existing = (found.data?.records ?? []).find(
    (row: { username: string }) => row.username === PEER_USERNAME,
  );
  if (existing) return existing.id as number;
  const created = await request.post(`${API}/api/users`, {
    // 显式声明 charset：不带它时中文显示名会被按本地代码页解成乱码（实测踩过）
    headers: { ...headers, "Content-Type": "application/json; charset=utf-8" },
    data: {
      username: PEER_USERNAME,
      password: PEER_PASSWORD,
      displayName: PEER_DISPLAY_NAME,
      role: "USER",
    },
  });
  expect(created.ok(), await created.text()).toBeTruthy();
  return (await created.json()).data.id as number;
}

test("记忆权限：对端账号自己聊出来的记忆，超管能按中文名/用户名/ID 搜到并查看", async ({
  page,
  request,
  browser,
}) => {
  // 这条用例要跑两个浏览器登录 + 两轮模型调用 + 三次搜索，300s 实测不够（会卡在收尾处超时）
  test.setTimeout(600_000);
  const t0 = Date.now();
  const mark = (step: string) =>
    console.log(`[记忆权限] +${((Date.now() - t0) / 1000).toFixed(0)}s ${step}`);

  const stamp = Date.now();
  const MARK = `MEM-XUSER-${stamp}`;
  /** 桩让模型“记住”的内容：只能经由对端自己的对话进入它的记忆 */
  const MEMORY_CONTENT = `用户名叫「小王」，偏好简洁回答。${MARK}`;

  const token = await apiToken(request);
  const adminHeaders = { Authorization: `Bearer ${token}` };
  const jsonHeaders = { ...adminHeaders, "Content-Type": "application/json" };

  const me = await (await request.get(`${API}/api/auth/me`, { headers: adminHeaders })).json();
  const adminId = (me.data?.id ?? me.data?.userId) as number;
  expect(adminId).toBeTruthy();

  // ① 对端账号：每轮开始先把它的记忆清空（MEMORY.md 不能删，只能清空正文）
  mark("开始：准备对端账号");
  const peerId = await ensurePeer(request, adminHeaders);
  const cleared = await request.put(`${API}/api/memory/MEMORY.md?userId=${peerId}`, {
    headers: jsonHeaders,
    data: { content: "" },
  });
  expect(cleared.ok(), await cleared.text()).toBeTruthy();

  const peerToken = (
    await (
      await request.post(`${API}/api/auth/login`, {
        data: { username: PEER_USERNAME, password: PEER_PASSWORD },
      })
    ).json()
  ).data.token as string;
  const peerHeaders = { Authorization: `Bearer ${peerToken}` };

  // ② 拉起“会调 memory_save”的模型桩，并用它造一个 Agent
  const stub = await startToolStub(
    TOOL_STUB_PORT,
    "memory_save",
    JSON.stringify({ content: MEMORY_CONTENT }),
  );
  mark(`对端就绪（#${peerId}），起模型桩`);
  const stubAgent = await createStubAgent(request, stamp, {
    modelId: `stub-memsave-${stamp}`,
    baseUrl: `http://localhost:${stub.port}/v1`,
  });

  const peerCtx = await browser.newContext();
  const peerPage = await peerCtx.newPage();

  try {
    // ③ 用浏览器真的登录对端，让它跟 Agent 聊一轮 → 模型调 memory_save → 写进它自己的 MEMORY.md
    await login(peerPage, PEER_USERNAME, PEER_PASSWORD);
    await peerPage.waitForSelector("[data-testid='chat-input']", { timeout: 30_000 });
    await peerPage.getByTitle("新建会话").click();
    const agentSelect = peerPage.locator("select").first();
    await expect(agentSelect).toContainText(stubAgent.agentName, { timeout: 20_000 });
    await agentSelect.selectOption({ label: stubAgent.agentName });

    const input = peerPage.getByTestId("chat-input");
    await input.fill(`记住：${MEMORY_CONTENT}`);
    await input.press("Enter");
    // 桩收到工具结果后会回一段 stub-ok 文本，说明这一轮真的跑完了
    await expect(peerPage.getByText(/stub-ok/).last()).toBeVisible({ timeout: 120_000 });
    mark(`对端（#${peerId}）自己聊完一轮 ✓`);

    // 记忆是对端通过对话产生的（用例没写过它的记忆），轮询到写进去为止
    await expect
      .poll(() => memoryOf(request, adminHeaders, peerId), {
        timeout: 90_000,
        intervals: [500, 1000, 2000],
      })
      .toContain(MARK);
    mark(`对端的 MEMORY.md 里真的出现了 ${MARK} ✓`);

    // ④ 权限：普通用户读别人的记忆必须被拒；读自己的可以
    const forbidden = await request.get(`${API}/api/memory?userId=${adminId}`, {
      headers: peerHeaders,
    });
    expect(forbidden.status(), "普通用户不该能读超管的记忆").toBe(403);
    expect((await request.get(`${API}/api/memory`, { headers: peerHeaders })).ok()).toBeTruthy();
    mark("接口侧：普通用户越权 403 ✓");

    // ⑤ 超管页面上**按中文显示名搜**（真实数据里显示名常是中文）
    mark("超管：开始登录");
    await login(page, "admin", "Admin@123");
    mark("超管：已登录，进记忆页");
    await page.getByRole("link", { name: "记忆" }).click();
    await expect(page.getByTestId("memory-page")).toBeVisible({ timeout: 20_000 });
    mark("超管：记忆页已显示");
    await expect(page.getByTestId("memory-viewing-other")).toHaveCount(0); // 默认是自己

    await searchAndPick(page, PEER_DISPLAY_NAME);
    await expect(page.getByTestId("memory-user-current")).toContainText(`#${peerId}`);
    const curated = page.getByTestId("memory-file-item").first();
    await expect(curated).toContainText("长期记忆", { timeout: 20_000 });
    await curated.click();
    await expect(page.getByTestId("memory-current-name")).toHaveText("MEMORY.md");
    await expect(page.getByTestId("memory-preview")).toContainText(MARK, { timeout: 20_000 });
    await page.screenshot({
      path: "../docs/acceptance/task-11.5-memory-admin-cross-user.png",
      fullPage: true,
    });
    mark("页面侧：按中文名搜到对端 → 看到它自己写的记忆 ✓");

    // ⑥ **按用户名**、**按用户 ID** 也要能精确搜到（排查现场通常只有其中一个）
    await page.getByTestId("memory-user-clear").click();
    await expect(page.getByTestId("memory-viewing-other")).toHaveCount(0);
    await searchAndPick(page, PEER_USERNAME);
    await expect(page.getByTestId("memory-user-current")).toContainText(`#${peerId}`);
    await page.getByTestId("memory-user-clear").click();
    await searchAndPick(page, String(peerId));
    await expect(page.getByTestId("memory-user-current")).toContainText(`#${peerId}`);
    mark("页面侧：按用户名 / 按 ID 都能搜到同一个人 ✓");

    // ⑦ 对端自己进记忆页：只看得到自己的，且**没有**用户搜索框
    await peerPage.getByRole("link", { name: "记忆" }).click();
    await expect(peerPage.getByTestId("memory-page")).toBeVisible({ timeout: 20_000 });
    await expect(peerPage.getByTestId("memory-user-search")).toHaveCount(0);
    await expect(peerPage.getByTestId("memory-file-item").first()).toContainText("长期记忆");
    await peerPage.getByTestId("memory-file-item").first().click();
    await expect(peerPage.getByTestId("memory-preview")).toContainText(MARK, { timeout: 20_000 });
    mark("页面侧：对端只看到自己的记忆，没有搜人入口 ✓");
  } finally {
    // ⑧ 收尾：会话 → 记忆正文（清空，MEMORY.md 不许删）→ 工作区目录 → Agent → 桩。
    //    对端账号本身留着复用：它已有 token_usage（审计数据），删用户会被外键拦住。
    await peerCtx.close();
    const sessions = await (await request.get(`${API}/api/sessions`, { headers: peerHeaders })).json();
    for (const session of (sessions.data ?? []) as { id: number }[]) {
      await request.delete(`${API}/api/sessions/${session.id}`, { headers: peerHeaders });
    }
    await request.put(`${API}/api/memory/MEMORY.md?userId=${peerId}`, {
      headers: jsonHeaders,
      data: { content: "" },
    });
    rmSync(resolve(HERE, "../../backend/agent-server/.myagent-workspace", String(peerId)), {
      recursive: true,
      force: true,
    });
    await deleteStubAgent(request, stubAgent.headers, stubAgent.agentDbId);
    await stub.stop();
  }
});
